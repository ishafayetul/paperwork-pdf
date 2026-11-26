package org.paperwork.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;

import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.PDFDocument;
import com.artifex.mupdf.fitz.PDFPage;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.zip.ZipFile;

public final class MobileCheck extends Instrumentation {
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        File directory = new File(getTargetContext().getCacheDir(), "paperwork-smoke");
        directory.mkdirs();
        try {
            securityChecks();
            evaluate(directory);
            report.putString("checks", "Scanner geometry, filters and PDF plus all 13 PDF tools passed.");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            report.putString("failure", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, report);
        } finally {
            for (File file : Objects.requireNonNull(directory.listFiles())) file.delete();
            directory.delete();
        }
    }

    private void securityChecks() throws Exception {
        android.content.Context app = getTargetContext();
        android.content.pm.PackageManager pm = app.getPackageManager();
        android.content.pm.PackageInfo info = pm.getPackageInfo(app.getPackageName(),
                android.content.pm.PackageManager.GET_PERMISSIONS);
        check(info.requestedPermissions == null || info.requestedPermissions.length == 0,
                "no network, broad storage or camera permission");
        int flags = info.applicationInfo.flags;
        check((flags & (android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
                | android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)) == 0, "release and backup flags");
        android.content.pm.ProviderInfo provider = pm.resolveContentProvider(app.getPackageName() + ".camera", 0);
        check(provider != null && !provider.exported && provider.grantUriPermissions, "private camera provider");
        android.net.Uri bad = new android.net.Uri.Builder().scheme("content").authority(provider.authority)
                .appendPath("..").appendPath("scan-draft").appendPath("pages.json").build();
        try (android.os.ParcelFileDescriptor ignored = app.getContentResolver().openFileDescriptor(bad, "r")) {
            throw new AssertionError("camera provider accepted a non-camera path");
        } catch (java.io.FileNotFoundException expected) {}
    }
    private void evaluate(File directory) throws Exception {
        File source = new File(directory, "fixture.pdf");
        PdfDocument fixture = new PdfDocument();
        try {
            for (int i = 0; i < 3; i++) {
                PdfDocument.Page page = fixture.startPage(new PdfDocument.PageInfo.Builder(360, 500, i + 1).create());
                Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
                pen.setColor(Color.BLACK);
                pen.setTextSize(22);
                page.getCanvas().drawText("Paperwork fixture " + (i + 1), 32, 81, pen);
                fixture.finishPage(page);
            }
            try (FileOutputStream stream = new FileOutputStream(source)) { fixture.writeTo(stream); }
        } finally {
            fixture.close();
        }
        PdfTools.Input input = new PdfTools.Input(source, "Fixture.pdf", "", 3);
        check(PdfTools.inspect(source, "") == 3, "PDF inspection");
        Bitmap preview = PdfTools.preview(input, 0, 0);
        check(preview.getWidth() > 350, "PDF page render");
        preview.recycle();

        PdfTools.Job job = job("merge", input);
        job.inputs.add(input);
        check(PdfTools.inspect(run(directory, job), "") == 6, "merge");

        job = job("split", input);
        job.ranges = "2–3, 1";
        check(PdfTools.inspect(run(directory, job), "") == 3, "extract");
        job.separate = true;
        try (ZipFile zip = new ZipFile(run(directory, job))) {
            check(zip.size() == 2 && zip.getEntry("part-0002.pdf") != null, "split ZIP");
        }
        check(run(directory, job("compress", input)).length() <= source.length(), "compress");

        job = job("organize", input);
        job.order.add(new int[]{1, 90});
        job.order.add(new int[]{1, 0});
        job.order.add(new int[]{-1, 0});
        File organized = run(directory, job);
        check(PdfTools.inspect(organized, "") == 3 && width(organized, 0) == 500
                && width(organized, 1) == 360, "organize / rotate / duplicate");

        job = job("crop", input);
        job.page = 1;
        job.box = new float[]{.1f,.15f,.9f,.80f};
        File cropped = run(directory, job);
        check(Math.abs(width(cropped, 1) - 288) < 1 && width(cropped, 0) == 360, "crop dimensions");

        job = job("markdown", input);
        String md = new String(Files.readAllBytes(run(directory, job).toPath()), StandardCharsets.UTF_8);
        check(md.contains("## Page 3") && md.contains("Paperwork fixture 2"), "Markdown extraction");

        job = job("images", input);
        job.jpeg = true;
        job.ranges = "1,3";
        try (ZipFile zip = new ZipFile(run(directory, job))) {
            check(zip.size() == 2 && zip.getEntry("page-0003.jpg") != null, "JPG conversion");
            try (java.io.InputStream stream = zip.getInputStream(zip.getEntry("page-0001.jpg"))) {
                Bitmap exported = android.graphics.BitmapFactory.decodeStream(stream);
                check(exported.getWidth() == 720 && exported.getHeight() == 1000, "image DPI");
                exported.recycle();
            }
        }
        job.jpeg = false;
        try (ZipFile zip = new ZipFile(run(directory, job))) {
            check(zip.getEntry("page-0001.png") != null, "PNG conversion");
        }

        job = job("edit", input);
        job.box = new float[]{.10f,.28f,.90f,.50f};
        job.text = "Reviewed";
        for (String kind : new String[]{"Text", "Highlight", "Rectangle"}) {
            job.kind = kind;
            check(notes(run(directory, job), 0) == 1, "edit " + kind);
        }
        job = job("watermark", input);
        job.text = "DRAFT";
        check(notes(run(directory, job), 2) == 1, "watermark");
        check(notes(run(directory, job("numbers", input)), 2) == 1, "page numbers");

        job = job("protect", input);
        job.text = "Fixture!892";
        File encrypted = run(directory, job);
        try {
            PdfTools.inspect(encrypted, "wrong");
            throw new AssertionError("wrong password was accepted");
        } catch (PdfTools.Locked expected) {}
        check(PdfTools.inspect(encrypted, job.text) == 3, "password-protected PDF");
        job = job("unlock", new PdfTools.Input(encrypted, "Fixture.pdf", job.text, 3));
        check(PdfTools.inspect(run(directory, job), "") == 3, "unlock");

        Bitmap photo = Bitmap.createBitmap(640, 800, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(photo);
        canvas.drawColor(0xff354f48);
        Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        pen.setColor(0xffd3c5ae);
        Path paper = new Path();
        paper.moveTo(105, 98); paper.lineTo(565, 145); paper.lineTo(512, 686);
        paper.lineTo(66, 734); paper.close();
        canvas.drawPath(paper, pen);
        pen.setColor(0xff23201d);
        pen.setTextSize(30);
        canvas.drawText("Camera document", 155, 270, pen);
        float[] corners = ScanImage.detect(photo);
        check(ScanMath.valid(corners) && corners[0] > .10 && corners[1] > .07, "detected corners");
        Bitmap color = ScanImage.correct(photo, corners, 1, 0);
        Bitmap mono = ScanImage.correct(photo, corners, 3, 90);
        check(color.getWidth() < 550 && mono.getWidth() == color.getHeight(), "perspective / rotation");
        check(ScanMath.gray(color.getPixel(28, 29)) > 225, "paper brightening");
        check(mono.getPixel(30, 40) == Color.WHITE || mono.getPixel(30, 40) == 0xff111111, "black and white");
        File stamp = new File(directory, "stamp.png");
        try (FileOutputStream stream = new FileOutputStream(stamp)) {
            check(color.compress(Bitmap.CompressFormat.PNG, 100, stream), "stamp encoding");
        }
        job = job("edit", input);
        job.kind = "Image";
        job.stamp = stamp;
        job.box = new float[]{.12f,.35f,.70f,.80f};
        check(notes(run(directory, job), 0) == 1, "edit image stamp");

        File scan = new File(directory, "scan.pdf");
        PDFDocument scans = new PDFDocument();
        try {
            PdfTools.addScan(scans, color, 89);
            PdfTools.addScan(scans, mono, 89);
            PdfTools.save(scans, scan);
        } finally {
            scans.destroy();
            photo.recycle();
            color.recycle();
            mono.recycle();
        }
        check(PdfTools.inspect(scan, "") == 2 && width(scan, 0) == 595 && width(scan, 1) == 842,
                "multi-page images to PDF");
    }

    private static PdfTools.Job job(String key, PdfTools.Input source) {
        PdfTools.Job job = new PdfTools.Job();
        job.key = key;
        job.inputs.add(source);
        return job;
    }

    private static File run(File directory, PdfTools.Job job) throws Exception {
        File output = File.createTempFile(job.key, job.extension(), directory);
        PdfTools.export(job, output);
        check(output.length() > 50, job.key + " output");
        return output;
    }

    private static float width(File file, int pageIndex) {
        Document document = Document.openDocument(file.getAbsolutePath());
        Page page = document.loadPage(pageIndex);
        try {
            Rect r = page.getBounds();
            return r.x1 - r.x0;
        } finally {
            page.destroy();
            document.destroy();
        }
    }

    private static int notes(File file, int pageIndex) {
        Document document = Document.openDocument(file.getAbsolutePath());
        PDFPage page = (PDFPage) document.loadPage(pageIndex);
        try {
            return page.getAnnotations().length;
        } finally {
            page.destroy();
            document.destroy();
        }
    }

    private static void check(boolean success, String item) {
        if (!success) throw new AssertionError(item);
    }
}
