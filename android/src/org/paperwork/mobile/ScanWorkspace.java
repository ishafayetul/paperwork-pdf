package org.paperwork.mobile;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AtomicFile;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.artifex.mupdf.fitz.PDFDocument;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ScanWorkspace {
    private static final int MAX_PAGES = 60;
    private final MainActivity app;
    final List<Scan> scans = new ArrayList<>();
    private final File directory;
    private final AtomicFile manifest;
    private final boolean cameraWorkspace;
    int selected;
    boolean cropping;

    static final class Scan {
        final File photo;
        float[] corners;
        int effect, rotation;

        Scan(File photo, float[] corners, int effect, int rotation) {
            this.photo = photo;
            this.corners = corners;
            this.effect = effect;
            this.rotation = rotation;
        }
    }

    ScanWorkspace(MainActivity app, boolean cameraWorkspace) {
        this.app = app;
        this.cameraWorkspace = cameraWorkspace;
        directory = new File(app.getFilesDir(), cameraWorkspace ? "scan-draft" : "image-draft");
        directory.mkdirs();
        manifest = new AtomicFile(new File(directory, "pages.json"));
        try {
            JSONArray entries = new JSONArray(new String(manifest.readFully(), StandardCharsets.UTF_8));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                String name = item.getString("photo");
                if (!name.matches("scan-[0-9]+\\.jpg")) continue;
                File path = new File(directory, name);
                if (!path.isFile()) continue;
                float[] corners = new float[8];
                JSONArray coords = item.getJSONArray("corners");
                for (int j = 0; j < corners.length; j++) corners[j] = (float) coords.getDouble(j);
                if (!ScanMath.valid(corners)) corners = ScanMath.full();
                scans.add(new Scan(path, corners, item.getInt("effect"), item.getInt("rotation")));
            }
        } catch (IOException | JSONException ignored) {
            // A fresh installation or an unreadable manifest starts with an empty draft.
        }
    }

    private boolean persist() {
        FileOutputStream stream = null;
        try {
            JSONArray entries = new JSONArray();
            for (Scan scan : scans) {
                JSONArray corners = new JSONArray();
                for (float point : scan.corners) corners.put(point);
                entries.put(new JSONObject().put("photo", scan.photo.getName()).put("corners", corners)
                        .put("effect", scan.effect).put("rotation", scan.rotation));
            }
            stream = manifest.startWrite();
            stream.write(entries.toString().getBytes(StandardCharsets.UTF_8));
            manifest.finishWrite(stream);
            return true;
        } catch (IOException | JSONException error) {
            if (stream != null) manifest.failWrite(stream);
            app.notice("The scan draft could not be saved on this device.");
            return false;
        }
    }

    void newScan() {
        if (scans.isEmpty()) {
            show();
            return;
        }
        new AlertDialog.Builder(app).setTitle("Start a new scan?")
                .setMessage("This discards the pages in your current scan draft. Any exported PDFs will be kept.")
                .setNegativeButton("Cancel", null).setPositiveButton("Discard draft", (dialog, which) -> {
                    List<Scan> previous = new ArrayList<>(scans);
                    scans.clear();
                    if (!persist()) {
                        scans.addAll(previous);
                        return;
                    }
                    selected = 0;
                    cropping = false;
                    for (Scan page : previous) page.photo.delete();
                    show();
                }).show();
    }

    void show() {
        app.screen = cameraWorkspace ? "scan" : "photos";
        LinearLayout body = app.screen(cameraWorkspace ? "Document scanner" : "Images to PDF",
                cameraWorkspace ? "Capture pages, correct the edges and export one PDF."
                        : "Arrange photos and export them together as a PDF.");
        LinearLayout add = Ui.row(app);
        if (cameraWorkspace) {
            Ui.weight(add, app.primary("Take photo", app::takePhoto));
            Ui.weight(add, app.neutral("Import photos", () -> app.pickImages(MainActivity.PICK_IMAGES)));
        } else {
            Ui.weight(add, app.primary("Import photos", () -> app.pickImages(MainActivity.PICK_ORIGINALS)));
        }
        body.addView(add);
        Ui.gap(body, 13);
        if (cameraWorkspace && !scans.isEmpty()) {
            body.addView(app.neutral("New scan", this::newScan));
            Ui.gap(body, 8);
        }
        if (scans.isEmpty()) {
            app.empty(body, cameraWorkspace ? "scan" : "photos", "Add your first page",
                    cameraWorkspace ? "Use your camera or choose photos from your device."
                            : "Choose photos from your device.");
            return;
        }

        Scan page = scans.get(selected);
        LinearLayout position = Ui.row(app);
        Ui.weight(position, app.neutral("Previous", () -> select(selected - 1)));
        TextView count = app.text("Page " + (selected + 1) + " of " + scans.size(), 14, Ui.INK, true);
        count.setGravity(android.view.Gravity.CENTER);
        Ui.weight(position, count);
        Ui.weight(position, app.neutral("Next", () -> select(selected + 1)));
        position.getChildAt(0).setEnabled(selected > 0);
        position.getChildAt(2).setEnabled(selected < scans.size() - 1);
        body.addView(position);
        Ui.gap(body, 9);

        PagePreview preview = app.preview(body, cropping ? PagePreview.CORNERS : PagePreview.READ, points -> {
            page.corners = points;
            persist();
        });
        boolean showCrop = cropping;
        float[] corners = page.corners.clone();
        int previewEffect = page.effect, angle = page.rotation;
        app.loadPreview(preview, () -> {
            Bitmap image = ScanImage.read(page.photo, 1250);
            if (showCrop) return image;
            try {
                return ScanImage.correct(image, corners, previewEffect, angle);
            } finally {
                image.recycle();
            }
        }, cropping ? page.corners : null);
        Ui.gap(body, 10);

        if (cropping) {
            body.addView(app.text("Drag the four handles to the corners of the paper.", 14, Ui.SOFT, false));
            Ui.gap(body, 9);
            LinearLayout selection = Ui.row(app);
            Ui.weight(selection, app.neutral("Auto edges", () -> app.work("Finding corners…", () -> {
                Bitmap original = ScanImage.read(page.photo, 450);
                try {
                    return ScanImage.detect(original);
                } finally {
                    original.recycle();
                }
            }, detected -> {
                page.corners = detected;
                persist();
                preview.selection(detected);
            })));
            Ui.weight(selection, app.neutral("Reset", () -> {
                page.corners = ScanMath.full();
                preview.selection(page.corners);
                persist();
            }));
            Ui.weight(selection, app.primary("Apply crop", () -> {
                cropping = false;
                show();
            }));
            body.addView(selection);
        } else {
            body.addView(app.text("Scan effect", 16, Ui.INK, true));
            Ui.gap(body, 7);
            for (int row = 0; row < 2; row++) {
                LinearLayout effects = Ui.row(app);
                for (int column = 0; column < 2; column++) {
                    int effect = row * 2 + column;
                    Ui.weight(effects, Ui.button(app, ScanMath.FILTERS[effect], page.effect == effect ? Ui.GREEN : Ui.WHITE,
                            page.effect == effect ? Ui.WHITE : Ui.INK, () -> {
                                page.effect = effect;
                                persist();
                                show();
                            }));
                }
                body.addView(effects);
                Ui.gap(body, 7);
            }
            LinearLayout edit = Ui.row(app);
            Ui.weight(edit, app.neutral("Crop corners", () -> { cropping = true; show(); }));
            Ui.weight(edit, app.neutral("Rotate 90°", () -> {
                page.rotation = (page.rotation + 90) % 360;
                persist();
                show();
            }));
            body.addView(edit);
            Ui.gap(body, 8);
            LinearLayout order = Ui.row(app);
            Ui.weight(order, app.neutral("Move earlier", () -> move(-1)));
            Ui.weight(order, app.neutral("Move later", () -> move(1)));
            Ui.weight(order, app.neutral("Remove page", this::remove));
            order.getChildAt(0).setEnabled(selected > 0);
            order.getChildAt(1).setEnabled(selected < scans.size() - 1);
            body.addView(order);
            app.footer(app.primary("Export PDF", this::chooseQuality));
        }
    }

    private void select(int position) {
        if (position >= 0 && position < scans.size()) {
            selected = position;
            show();
        }
    }

    private void move(int offset) {
        int destination = selected + offset;
        if (destination < 0 || destination >= scans.size()) return;
        Collections.swap(scans, selected, destination);
        selected = destination;
        persist();
        show();
    }

    private void remove() {
        new AlertDialog.Builder(app).setTitle("Remove this scan page?")
                .setMessage("Other pages in the draft will be kept.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> {
                    int index = selected;
                    Scan removed = scans.remove(index);
                    if (persist()) {
                        selected = Math.max(0, Math.min(index, scans.size() - 1));
                        removed.photo.delete();
                    } else scans.add(index, removed);
                    show();
                }).show();
    }

    void add(List<Uri> images, File camera, boolean automatic) {
        if (scans.size() >= MAX_PAGES) {
            if (camera != null) camera.delete();
            app.notice("This draft already contains 60 pages. Export it or remove a page first.");
            return;
        }
        int available = MAX_PAGES - scans.size();
        app.work("Preparing scan pages…", () -> {
            ArrayList<Scan> imported = new ArrayList<>();
            if (camera != null) {
                try {
                    imported.add(makeScan(ScanImage.read(camera, 2600), true));
                } finally {
                    camera.delete();
                }
            }
            int unreadable = 0;
            for (Uri image : images) {
                if (imported.size() == available) break;
                try {
                    imported.add(makeScan(ScanImage.read(app.getContentResolver(), image, 2600), automatic));
                } catch (IOException | RuntimeException error) {
                    unreadable++;
                }
            }
            if (imported.isEmpty()) throw new IOException("No selected photos could be opened.");
            return new ImportResult(imported, unreadable);
        }, result -> {
            scans.addAll(result.pages);
            selected = scans.size() - 1;
            cropping = camera != null;
            persist();
            show();
            if (result.skipped > 0 || images.size() > available) {
                app.notice("Some photos were unreadable or exceeded the 60-page draft limit.");
            }
        });
    }

    private static final class ImportResult {
        final List<Scan> pages;
        final int skipped;
        ImportResult(List<Scan> pages, int skipped) { this.pages = pages; this.skipped = skipped; }
    }

    private Scan makeScan(Bitmap bitmap, boolean automatic) throws IOException {
        File path = null;
        try {
            if (bitmap.hasAlpha()) {
                Bitmap backed = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(backed);
                canvas.drawColor(android.graphics.Color.WHITE);
                canvas.drawBitmap(bitmap, 0, 0, null);
                bitmap.recycle();
                bitmap = backed;
            }
            float[] corners = automatic ? ScanImage.detect(bitmap) : ScanMath.full();
            path = Files.createTempFile(directory.toPath(), "scan-", ".jpg").toFile();
            try (FileOutputStream stream = new FileOutputStream(path)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw new IOException("Could not store a scan.");
            }
            return new Scan(path, corners, automatic ? 1 : 0, 0);
        } catch (IOException | RuntimeException error) {
            if (path != null) path.delete();
            throw error;
        } finally {
            bitmap.recycle();
        }
    }

    private void chooseQuality() {
        String[] labels = {"Compact · 1400 px", "Standard · 2000 px", "High · 2600 px"};
        final int[] choice = {1};
        new AlertDialog.Builder(app).setTitle("Scan quality").setSingleChoiceItems(labels, 1, (d, index) -> choice[0] = index)
                .setNegativeButton("Cancel", null).setPositiveButton("Continue", (d, ignored) -> makePdf(choice[0])).show();
    }

    private void makePdf(int quality) {
        app.makeOutput(cameraWorkspace ? "paperwork-scan.pdf" : "paperwork-images.pdf", MainActivity.PDF_MIME, output -> {
            int dimension = new int[]{1400, 2000, 2600}[quality];
            int jpeg = new int[]{70, 86, 94}[quality];
            PDFDocument document = new PDFDocument();
            try {
                for (Scan page : scans) {
                    Bitmap bitmap = ScanImage.read(page.photo, dimension);
                    Bitmap corrected = null;
                    try {
                        corrected = ScanImage.correct(bitmap, page.corners, page.effect, page.rotation);
                        PdfTools.addScan(document, corrected, jpeg);
                    } finally {
                        bitmap.recycle();
                        if (corrected != null) corrected.recycle();
                    }
                }
                PdfTools.save(document, output);
                return "Saved " + scans.size() + (scans.size() == 1 ? " scan page." : " scan pages.");
            } finally {
                document.destroy();
            }
        });
    }
}
