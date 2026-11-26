package org.paperwork.mobile;

import android.graphics.Bitmap;

import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.Image;
import com.artifex.mupdf.fitz.PDFAnnotation;
import com.artifex.mupdf.fitz.PDFDocument;
import com.artifex.mupdf.fitz.PDFGraftMap;
import com.artifex.mupdf.fitz.PDFObject;
import com.artifex.mupdf.fitz.PDFPage;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Quad;
import com.artifex.mupdf.fitz.Rect;
import com.artifex.mupdf.fitz.StructuredText;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PdfTools {
    private static final String SAVE = "garbage=deduplicate,compress,compress-images,compress-fonts";
    static final String[] KEYS = {"merge", "split", "compress", "edit", "organize", "crop",
            "photos", "images", "markdown", "watermark", "numbers", "protect", "unlock"};
    static final String[] TITLES = {"Merge PDF", "Split PDF", "Compress PDF", "Edit PDF", "Organize pages",
            "Crop PDF", "Images to PDF", "PDF to images", "PDF to Markdown", "Watermark",
            "Page numbers", "Protect PDF", "Unlock PDF"};
    static final String[] DESCRIPTIONS = {"Combine PDFs in the order you choose.",
            "Extract pages or split ranges into separate files.", "Reduce file size without lowering image quality.",
            "Add text, images, highlights or shapes over an existing page.", "Reorder, rotate, duplicate or remove pages.",
            "Set the visible area of a page.", "Combine photos or scans into a PDF.",
            "Export pages as PNG or JPEG in a ZIP.", "Export selectable text to editable notes.",
            "Place a text stamp on all pages.", "Add a footer to every page.",
            "Encrypt a PDF with an opening password.", "Open a protected PDF and save an unlocked copy."};

    static final class Input {
        final File file;
        final String name;
        final String password;
        final int count;

        Input(File file, String name, String password, int count) {
            this.file = file;
            this.name = name;
            this.password = password;
            this.count = count;
        }
    }

    static final class Locked extends IOException {
        Locked() { super("A password is required to open this PDF."); }
    }

    static final class Job {
        String key, ranges = "", text = "", kind = "Text";
        boolean separate, all, jpeg;
        int page, dpi = 144;
        float[] box = {};
        File stamp;
        List<int[]> order = new ArrayList<>();
        List<Input> inputs = new ArrayList<>();

        String extension() {
            if ("markdown".equals(key)) return ".md";
            if ("images".equals(key) || "split".equals(key) && separate) return ".zip";
            return ".pdf";
        }
    }

    private static PDFDocument open(File file, String password) throws IOException {
        Document document = Document.openDocument(file.getAbsolutePath());
        if (!(document instanceof PDFDocument)) {
            document.destroy();
            throw new IOException("Choose a readable PDF.");
        }
        if (document.needsPassword() && !document.authenticatePassword(password)) {
            document.destroy();
            throw new Locked();
        }
        return (PDFDocument) document;
    }

    static int inspect(File file, String password) throws IOException {
        PDFDocument doc = open(file, password);
        try {
            int count = doc.countPages();
            if (count < 1) throw new IOException("This PDF has no pages.");
            return count;
        } finally {
            doc.destroy();
        }
    }

    static Bitmap preview(Input source, int index, int degrees) throws IOException {
        PDFDocument document = open(source.file, source.password);
        try {
            Page page = document.loadPage(index);
            try {
                Rect b = page.getBounds();
                float scale = Math.min(2, 1100f / Math.max(1, Math.max(b.x1 - b.x0, b.y1 - b.y0)));
                return AndroidDrawDevice.drawPage(page, scale * 72, degrees);
            } finally {
                page.destroy();
            }
        } finally {
            document.destroy();
        }
    }

    static List<int[]> ranges(String value, int count, boolean split) throws IOException {
        List<int[]> result = new ArrayList<>();
        if (value.trim().isEmpty()) {
            if (split) for (int i = 0; i < count; i++) result.add(new int[]{i, i});
            else result.add(new int[]{0, count - 1});
            return result;
        }
        for (String token : value.split(",", -1)) {
            Matcher match = Pattern.compile("\\s*([0-9]{1,8})(?:\\s*-\\s*([0-9]{1,8}))?\\s*").matcher(token);
            if (!match.matches()) throw new IOException("Use page ranges such as 1–3, 6, or 8–10 (a hyphen also works).");
            int first = Integer.parseInt(match.group(1));
            int last = match.group(2) == null ? first : Integer.parseInt(match.group(2));
            if (first < 1 || last < first || last > count) {
                throw new IOException("Choose ascending ranges within pages 1–" + count + ".");
            }
            result.add(new int[]{first - 1, last - 1});
        }
        return result;
    }

    static void addScan(PDFDocument document, Bitmap image, int jpegQuality) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        if (!image.compress(Bitmap.CompressFormat.JPEG, jpegQuality, data)) throw new IOException("Could not encode a scan page.");
        Image photo = new Image(data.toByteArray());
        PDFObject resources = document.newDictionary();
        PDFObject objects = document.newDictionary();
        try {
            boolean landscape = image.getWidth() > image.getHeight();
            int w = landscape ? 842 : 595, h = landscape ? 595 : 842;
            double scale = Math.min((w - 18.0) / image.getWidth(), (h - 18.0) / image.getHeight());
            double fw = image.getWidth() * scale, fh = image.getHeight() * scale;
            PDFObject imageObject = document.addImage(photo);
            objects.put("Photo", imageObject);
            imageObject.destroy();
            resources.put("XObject", objects);
            String content = String.format(Locale.ROOT, "q %.3f 0 0 %.3f %.3f %.3f cm /Photo Do Q",
                    fw, fh, (w - fw) / 2, (h - fh) / 2);
            PDFObject page = document.addPage(new Rect(0, 0, w, h), 0, resources, content);
            document.insertPage(-1, page);
            page.destroy();
        } finally {
            resources.destroy();
            objects.destroy();
            photo.destroy();
        }
    }

    static void save(PDFDocument document, File output) {
        document.save(output.getAbsolutePath(), SAVE);
    }

    static String export(Job job, File output) throws IOException {
        if ("merge".equals(job.key)) {
            if (job.inputs.size() < 2) throw new IOException("Choose at least two PDFs to merge.");
            PDFDocument combined = new PDFDocument();
            try {
                for (Input item : job.inputs) {
                    PDFDocument source = open(item.file, item.password);
                    try {
                        graft(combined, source, ranges("", item.count, false));
                    } finally {
                        source.destroy();
                    }
                }
                save(combined, output);
                return "Saved " + combined.countPages() + " pages.";
            } finally {
                combined.destroy();
            }
        }
        Input source = job.inputs.get(0);
        PDFDocument doc = open(source.file, source.password);
        try {
            switch (job.key) {
                case "split":
                    return split(doc, job, output);
                case "images":
                    return images(doc, job, output);
                case "markdown":
                    return markdown(doc, output);
                case "organize":
                    return organize(doc, job, output);
                case "crop":
                    for (int i = 0; i < doc.countPages(); i++) {
                        if (job.all || job.page == i) {
                            PDFPage page = (PDFPage) doc.loadPage(i);
                            try {
                                page.setCropBox(area(page, job.box));
                            } finally {
                                page.destroy();
                            }
                        }
                    }
                    break;
                case "edit":
                    annotate(doc, job.page, job.box, job.kind, job.text, 17, 1, job.stamp);
                    break;
                case "watermark":
                case "numbers":
                    for (int i = 0; i < doc.countPages(); i++) {
                        boolean numbers = "numbers".equals(job.key);
                        annotate(doc, i, numbers ? new float[]{.30f,.91f,.70f,.98f}
                                        : new float[]{.12f,.40f,.88f,.59f},
                                "Text", numbers ? String.valueOf(i + 1) : job.text, numbers ? 12 : 28,
                                numbers ? 1 : .35f, null);
                    }
                    break;
                case "protect":
                    if (!job.text.matches("[!-+\\--~]{1,64}")) {
                        throw new IOException("Use 1–64 ASCII letters, numbers or symbols, with no commas or spaces.");
                    }
                    doc.save(output.getAbsolutePath(), SAVE + ",encrypt=aes-256,user-password=" + job.text
                            + ",owner-password=" + job.text + ",permissions=-4");
                    return "Saved a password-protected PDF.";
                case "unlock":
                    doc.save(output.getAbsolutePath(), SAVE + ",encrypt=none");
                    return "Saved an unencrypted PDF.";
                case "compress":
                    doc.save(output.getAbsolutePath(), SAVE + ",objstms");
                    long original = source.file.length(), result = output.length();
                    if (result >= original) {
                        Files.copy(source.file.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        return "The original was already smaller. Saved an identical copy.";
                    }
                    return String.format(Locale.ROOT, "Reduced file size by %.1f%%.", (1 - (double) result / original) * 100);
                default:
                    throw new IOException("Choose a PDF tool.");
            }
            save(doc, output);
            return "Saved " + doc.countPages() + " pages.";
        } finally {
            doc.destroy();
        }
    }

    private static void graft(PDFDocument output, PDFDocument source, List<int[]> spans) {
        PDFGraftMap map = output.newPDFGraftMap();
        try {
            for (int[] span : spans) for (int i = span[0]; i <= span[1]; i++) map.graftPage(-1, source, i);
        } finally {
            map.destroy();
        }
    }

    private static String split(PDFDocument source, Job job, File output) throws IOException {
        List<int[]> spans = ranges(job.ranges.replace('–', '-'), source.countPages(), job.separate);
        if (!job.separate) {
            PDFDocument part = new PDFDocument();
            try {
                graft(part, source, spans);
                save(part, output);
                return "Extracted " + part.countPages() + " pages.";
            } finally {
                part.destroy();
            }
        }
        File piece = File.createTempFile("piece-", ".pdf", output.getParentFile());
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            for (int i = 0; i < spans.size(); i++) {
                PDFDocument part = new PDFDocument();
                try {
                    graft(part, source, java.util.Collections.singletonList(spans.get(i)));
                    save(part, piece);
                } finally {
                    part.destroy();
                }
                zip.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "part-%04d.pdf", i + 1)));
                Files.copy(piece.toPath(), zip);
                zip.closeEntry();
            }
        } finally {
            piece.delete();
        }
        return "Created " + spans.size() + " PDFs.";
    }

    private static String organize(PDFDocument source, Job job, File output) throws IOException {
        if (job.order.isEmpty()) throw new IOException("Keep at least one page.");
        PDFDocument result = new PDFDocument();
        PDFGraftMap map = result.newPDFGraftMap();
        try {
            for (int i = 0; i < job.order.size(); i++) {
                int[] row = job.order.get(i);
                if (row[0] < 0) {
                    PDFObject blank = result.addPage(new Rect(0, 0, 595, 842), row[1], PDFObject.Null, "");
                    result.insertPage(-1, blank);
                    blank.destroy();
                } else {
                    map.graftPage(-1, source, row[0]);
                    if (row[1] != 0) {
                        PDFObject page = result.findPage(i);
                        PDFObject angle = page.getInheritable("Rotate");
                        page.put("Rotate", ((angle.isNumber() ? angle.asInteger() : 0) + row[1]) % 360);
                        angle.destroy();
                        page.destroy();
                    }
                }
            }
            save(result, output);
            return "Saved " + job.order.size() + " pages.";
        } finally {
            map.destroy();
            result.destroy();
        }
    }

    private static String images(PDFDocument source, Job job, File output) throws IOException {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        for (int[] span : ranges(job.ranges.replace('–', '-'), source.countPages(), false)) {
            for (int i = span[0]; i <= span[1]; i++) indexes.add(i);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            for (int index : indexes) {
                Page page = source.loadPage(index);
                Bitmap image = null;
                try {
                    Rect b = page.getBounds();
                    float scale = job.dpi / 72f;
                    float w = (b.x1 - b.x0) * scale, h = (b.y1 - b.y0) * scale;
                    if (w > 10500 || h > 10500 || w * h > 16_000_000) {
                        throw new IOException("A selected page is too large. Choose a lower resolution.");
                    }
                    image = AndroidDrawDevice.drawPage(page, job.dpi);
                    zip.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "page-%04d.%s", index + 1,
                            job.jpeg ? "jpg" : "png")));
                    if (!image.compress(job.jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 90, zip)) {
                        throw new IOException("Could not encode a PDF page.");
                    }
                    zip.closeEntry();
                } finally {
                    if (image != null) image.recycle();
                    page.destroy();
                }
            }
        }
        return "Exported " + indexes.size() + " page images.";
    }

    private static Rect area(PDFPage page, float[] box) throws IOException {
        if (box.length != 4 || !(0 <= box[0] && box[0] < box[2] && box[2] <= 1
                && 0 <= box[1] && box[1] < box[3] && box[3] <= 1)) throw new IOException("Draw an area on the page.");
        Rect r = page.getBounds();
        float w = r.x1 - r.x0, h = r.y1 - r.y0;
        return new Rect(r.x0 + box[0] * w, r.y0 + box[1] * h, r.x0 + box[2] * w, r.y0 + box[3] * h);
    }

    private static void annotate(PDFDocument doc, int index, float[] box, String kind, String text,
                                 float size, float opacity, File stamp) throws IOException {
        PDFPage page = (PDFPage) doc.loadPage(index);
        try {
            Rect r = area(page, box);
            boolean highlight = "Highlight".equals(kind);
            boolean square = "Rectangle".equals(kind);
            int type = stamp != null ? PDFAnnotation.TYPE_STAMP : highlight ? PDFAnnotation.TYPE_HIGHLIGHT
                    : square ? PDFAnnotation.TYPE_SQUARE : PDFAnnotation.TYPE_FREE_TEXT;
            PDFAnnotation note = page.createAnnotation(type);
            try {
                float[] ink = {.09f, .29f, .24f};
                if (stamp != null) {
                    Image image = new Image(stamp.getAbsolutePath());
                    try {
                        note.setRect(r);
                        note.setStampImage(image);
                    } finally {
                        image.destroy();
                    }
                } else if (highlight) {
                    note.setQuadPoints(new Quad[]{new Quad(r)});
                    note.setColor(new float[]{1f, .78f, .18f});
                    note.setOpacity(.40f);
                } else {
                    note.setRect(r);
                    note.setBorderWidth(square ? 1.4f : 0);
                    if (square) note.setColor(ink);
                    else {
                        note.setDefaultAppearance("Helv", Math.min(size, (r.y1 - r.y0) * .68f), ink);
                        note.setQuadding(opacity < 1 || size == 12 ? 1 : 0);
                        note.setContents(text);
                        note.setOpacity(opacity);
                    }
                }
                note.update();
                page.update();
            } finally {
                note.destroy();
            }
        } finally {
            page.destroy();
        }
    }

    private static String markdown(PDFDocument source, File output) throws IOException {
        boolean any = false;
        try (Writer writer = Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8)) {
            for (int i = 0; i < source.countPages(); i++) {
                Page page = source.loadPage(i);
                StructuredText text = null;
                try {
                    text = page.toStructuredText();
                    writer.write("## Page " + (i + 1) + "\n\n");
                    boolean content = false;
                    for (StructuredText.TextBlock block : text.getBlocks()) {
                        if (block.lines == null) continue;
                        for (StructuredText.TextLine line : block.lines) {
                            StringBuilder value = new StringBuilder();
                            for (StructuredText.TextChar character : line.chars) {
                                if (Character.isValidCodePoint(character.c)) value.appendCodePoint(character.c);
                            }
                            String formatted = value.toString().trim();
                            if (!formatted.isEmpty()) {
                                content = true;
                                boolean bullet = formatted.startsWith("• ");
                                if (bullet) formatted = formatted.substring(2);
                                writer.write((bullet ? "- " : "") + formatted.replaceAll("([\\\\`*_{}\\[\\]<>|])", "\\\\$1") + "\n");
                            }
                        }
                        writer.write("\n");
                    }
                    if (!content) writer.write("*No selectable text on this page.*\n\n");
                    any |= content;
                } finally {
                    if (text != null) text.destroy();
                    page.destroy();
                }
            }
        }
        if (!any) throw new IOException("No selectable text was found. This PDF may contain only scans.");
        return "Saved Markdown for " + source.countPages() + " pages.";
    }
}
