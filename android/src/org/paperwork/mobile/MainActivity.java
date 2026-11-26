package org.paperwork.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    static final int CAPTURE = 40, PICK_IMAGES = 41, PICK_PDF = 42, SAVE_AS = 43, PICK_ORIGINALS = 44,
            PICK_STAMP = 45;
    static final String PDF_MIME = "application/pdf";

    interface Exporter { String write(File file) throws IOException; }

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final HashSet<Uri> inputUris = new HashSet<>();
    private LinearLayout shell;
    private ScrollView scroll;
    private PagePreview activePreview;
    private int previewEpoch;
    private String cameraName, pendingMessage, pendingMime;
    private File pending;
    private boolean busy;
    private volatile boolean destroyed;
    private ScanWorkspace scanner, photos;
    private PdfWorkspace pdf;
    String screen = "home";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scanner = new ScanWorkspace(this, true);
        photos = new ScanWorkspace(this, false);
        pdf = new PdfWorkspace(this);
        if (state != null) {
            cameraName = state.getString("camera");
            String file = state.getString("pending");
            if (file != null && file.matches("export-[0-9]+\\.tmp")) pending = new File(getCacheDir(), file);
            pendingMessage = state.getString("message");
            pendingMime = state.getString("mime");
            screen = state.getString("screen", "home");
        }
        if ("scan".equals(screen)) scanner.show();
        else if ("photos".equals(screen)) photos.show();
        else if ("saved".equals(screen)) savedPdfs();
        else if ("pdf".equals(screen) && state != null && state.getInt("tool", -1) >= 0) {
            pdf.start(state.getInt("tool"));
        } else home();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("screen", screen);
        out.putString("camera", cameraName);
        out.putString("message", pendingMessage);
        out.putString("mime", pendingMime);
        if (pending != null) out.putString("pending", pending.getName());
        out.putInt("tool", pdf.tool);
    }

    void home() {
        screen = "home";
        LinearLayout body = screen("Paperwork", "");
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(Ui.shape(Ui.GREEN, 0, 20, this));
        hero.setClipToOutline(true);
        Ui.Glyph art = new Ui.Glyph(this, "hero", Ui.LIME);
        FrameLayout.LayoutParams artLocation = new FrameLayout.LayoutParams(dp(139), dp(139),
                Gravity.BOTTOM | Gravity.END);
        artLocation.rightMargin = dp(5);
        hero.addView(art, artLocation);
        LinearLayout words = Ui.column(this);
        words.setPadding(dp(17), dp(18), dp(17), dp(17));
        TextView headline = text("Scan to PDF", 31, Ui.WHITE, true);
        words.addView(headline);
        Ui.gap(words, 8);
        TextView detail = text("Turn paper into clean,\nshareable scans.", 15, 0xffd7e8db, false);
        detail.setLineSpacing(dp(3), 1f);
        words.addView(detail);
        Ui.gap(words, 15);
        words.addView(Ui.button(this, "New scan", Ui.LIME, 0xff172f1d, scanner::newScan),
                new LinearLayout.LayoutParams(-2, -2));
        if (!scanner.scans.isEmpty()) {
            Ui.gap(words, 6);
            words.addView(neutral("Continue scan", scanner::show), new LinearLayout.LayoutParams(-2, -2));
        }
        hero.addView(words);
        body.addView(hero);
        Ui.gap(body, 21);
        LinearLayout header = Ui.row(this);
        TextView title = text("PDF tools", 22, Ui.INK, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(text("On-device", 12, Ui.SOFT, true));
        body.addView(header);
        Ui.gap(body, 11);
        for (int i = 0; i < PdfTools.KEYS.length; i += 2) {
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.TOP);
            Ui.weight(row, card(i));
            if (i + 1 < PdfTools.KEYS.length) Ui.weight(row, card(i + 1));
            else Ui.weight(row, new View(this));
            body.addView(row);
            Ui.gap(body, 8);
        }
    }

    private void savedPdfs() {
        screen = "saved";
        LinearLayout body = screen("Saved PDFs", "");
        work("Finding saved PDFs…", () -> {
            List<UriPermission> grants = new ArrayList<>(getContentResolver().getPersistedUriPermissions());
            grants.sort(Comparator.comparingLong(UriPermission::getPersistedTime).reversed());
            // Only successful PDF exports retain a read grant.
            LinkedHashMap<Uri, String> documents = new LinkedHashMap<>();
            for (UriPermission grant : grants) {
                if (!grant.isReadPermission()) continue;
                Uri uri = grant.getUri();
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String name = cursor.getString(0);
                        documents.put(uri, name == null || name.isEmpty() ? "Saved PDF" : name);
                    }
                } catch (RuntimeException ignored) {
                    // The document may have moved or its provider may be unavailable.
                }
            }
            return documents;
        }, documents -> {
            if (documents.isEmpty()) {
                empty(body, "pdf", "No saved PDFs", "PDF exports you save from Paperwork appear here.");
            }
            documents.forEach((uri, name) -> {
                body.addView(neutral(name, () -> openResult(uri, PDF_MIME)));
                Ui.gap(body, 8);
            });
        });
    }

    private View card(int index) {
        String key = PdfTools.KEYS[index];
        int tint = index == 2 ? 0xfff3e8d2
                : index == 3 || index == 5 || index == 9 ? 0xffeee8f8
                : index >= 6 && index <= 8 ? 0xffe4eff7 : index >= 11 ? 0xfff3e7ee : 0xffe5f1e6;
        LinearLayout card = Ui.column(this);
        Ui.interactive(card, Ui.WHITE, Ui.BORDER, 14);
        card.setMinimumHeight(dp(119));
        card.setPadding(dp(11), dp(10), dp(9), dp(12));
        card.setFocusable(true);
        card.setContentDescription(PdfTools.TITLES[index] + ". " + PdfTools.DESCRIPTIONS[index]);
        card.setOnClickListener(v -> {
            if ("photos".equals(key)) {
                photos.show();
            } else {
                pdf.start(index);
            }
        });
        Ui.Glyph icon = new Ui.Glyph(this, key, Ui.GREEN);
        icon.setBackground(Ui.shape(tint, 0, 9, this));
        card.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        Ui.gap(card, 6);
        card.addView(text(PdfTools.TITLES[index], 15, Ui.INK, true));
        Ui.gap(card, 4);
        card.addView(text(PdfTools.DESCRIPTIONS[index], 12, Ui.SOFT, false));
        return card;
    }

    LinearLayout screen(String title, String intro) {
        previewEpoch++;
        if (activePreview != null) activePreview.release();
        activePreview = null;
        shell = Ui.column(this);
        shell.setBackgroundColor(Ui.BG);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout bar = Ui.row(this);
        bar.setPadding(dp(15), dp(6), dp(15), dp(7));
        if ("home".equals(screen)) {
            Ui.Glyph icon = new Ui.Glyph(this, "pdf", Ui.GREEN);
            icon.setBackground(Ui.shape(Ui.LIME, 0, 10, this));
            bar.addView(icon, new LinearLayout.LayoutParams(dp(35), dp(35)));
            TextView name = text(title, 23, Ui.INK, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = dp(9);
            bar.addView(name, lp);
            bar.addView(neutral("Saved PDFs", this::savedPdfs));
        } else {
            TextView back = neutral("‹  Tools", this::home);
            bar.addView(back);
            TextView name = text(title, 21, Ui.INK, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = dp(11);
            bar.addView(name, lp);
        }
        shell.addView(bar);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = Ui.column(this);
        content.setPadding(dp(15), dp(8), dp(15), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        if (!intro.isEmpty()) {
            content.addView(text(intro, 14, Ui.SOFT, false));
            Ui.gap(content, 12);
        }
        setContentView(shell);
        shell.requestApplyInsets();
        return content;
    }

    void empty(LinearLayout body, String key, String title, String description) {
        LinearLayout box = Ui.column(this);
        box.setBackground(Ui.shape(0xffeaf1e9, Ui.BORDER, 16, this));
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(15), dp(23), dp(15), dp(23));
        box.addView(new Ui.Glyph(this, key, Ui.GREEN), new LinearLayout.LayoutParams(dp(46), dp(46)));
        Ui.gap(box, 8);
        TextView heading = text(title, 19, Ui.INK, true);
        heading.setGravity(Gravity.CENTER);
        box.addView(heading);
        Ui.gap(box, 6);
        TextView detail = text(description, 14, Ui.SOFT, false);
        detail.setGravity(Gravity.CENTER);
        box.addView(detail);
        body.addView(box);
    }

    PagePreview preview(LinearLayout body, int mode, PagePreview.Changed change) {
        PagePreview view = new PagePreview(this, mode, change);
        int height = Math.min(370, Math.max(230, getResources().getConfiguration().screenHeightDp * 41 / 100));
        body.addView(view, new LinearLayout.LayoutParams(-1, dp(height)));
        activePreview = view;
        return view;
    }

    void footer(TextView action) {
        LinearLayout panel = Ui.column(this);
        panel.setPadding(dp(15), dp(7), dp(15), dp(8));
        panel.setBackgroundColor(Ui.BG);
        panel.addView(action);
        shell.addView(panel);
    }

    void loadPreview(PagePreview view, Callable<Bitmap> source, float[] selection) {
        int epoch = ++previewEpoch;
        background.execute(() -> {
            if (destroyed) return;
            Bitmap bitmap = null;
            try {
                bitmap = source.call();
            } catch (Exception ignored) {
                // A preview is optional while the input/output workflow stays available.
            }
            Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (destroyed || epoch != previewEpoch || view != activePreview) {
                    if (result != null) result.recycle();
                } else if (result != null) view.setPage(result, selection);
                else notice("This page could not be previewed.");
            });
        });
    }

    void pickImages(int code) { pick("image/*", true, code); }

    void pick(String mime, boolean multi, int code) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multi);
        try {
            startActivityForResult(intent, code);
        } catch (ActivityNotFoundException error) {
            notice("The Android file picker is unavailable.");
        }
    }

    void takePhoto() {
        if (busy) return;
        String name = UUID.randomUUID() + ".jpg";
        File file = CameraFileProvider.captureFile(this, name);
        Uri uri = CameraFileProvider.uri(name);
        try {
            if ((!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
                    || !file.createNewFile()) throw new IOException();
            cameraName = name;
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Document photo", uri));
            startActivityForResult(intent, CAPTURE);
        } catch (ActivityNotFoundException | IOException | SecurityException error) {
            file.delete();
            cameraName = null;
            notice("Could not open a camera app. You can still import photos.");
        }
    }

    static List<Uri> choices(Intent data) {
        if (data == null) return Collections.emptyList();
        ArrayList<Uri> result = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && "content".equals(uri.getScheme()) && !result.contains(uri)) result.add(uri);
            }
        } else if (data.getData() != null && "content".equals(data.getData().getScheme())) result.add(data.getData());
        return result;
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == CAPTURE) {
            if (cameraName == null || !cameraName.matches("[0-9a-f-]{36}\\.jpg")) return;
            Uri uri = CameraFileProvider.uri(cameraName);
            File file = CameraFileProvider.captureFile(this, cameraName);
            revokeUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cameraName = null;
            if (result == RESULT_OK && file.length() > 0) scanner.add(Collections.emptyList(), file, true);
            else {
                file.delete();
                if (result == RESULT_OK) notice("The camera returned no full-resolution photo.");
            }
        } else if (code == SAVE_AS) {
            File export = pending;
            String mime = pendingMime, message = pendingMessage;
            pending = null;
            pendingMime = null;
            pendingMessage = null;
            if (export == null) return;
            List<Uri> output = choices(data);
            if (result == RESULT_OK && output.size() == 1) {
                Uri uri = output.get(0);
                if (inputUris.contains(uri)) {
                    export.delete();
                    notice("Choose a new destination to preserve the source file.");
                    return;
                }
                work("Saving your file…", () -> {
                    try (InputStream in = Files.newInputStream(export.toPath())) {
                        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                            if (out == null) throw new IOException("The selected folder is unavailable.");
                            transfer(in, out, Long.MAX_VALUE);
                        }
                    } finally {
                        export.delete();
                    }
                    return !PDF_MIME.equals(mime) || rememberPdf(uri, data.getFlags());
                }, listed -> {
                    String detail = listed ? message : message + " Open it from the folder you chose.";
                    new AlertDialog.Builder(this).setTitle("Export complete").setMessage(detail)
                            .setPositiveButton("Done", null)
                            .setNeutralButton("Open file", (dialog, ignored) -> openResult(uri, mime))
                            .show();
                });
            } else export.delete();
        } else if (result == RESULT_OK) {
            List<Uri> inputs = choices(data);
            if (inputs.isEmpty()) return;
            inputUris.addAll(inputs);
            if (code == PICK_IMAGES) scanner.add(inputs, null, true);
            else if (code == PICK_ORIGINALS) photos.add(inputs, null, false);
            else if (code == PICK_PDF) pdf.add(inputs);
            else if (code == PICK_STAMP) pdf.addStamp(inputs.get(0));
        }
    }

    private boolean rememberPdf(Uri uri, int flags) {
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        try {
            if ((flags & required) != required || DocumentsContract.isTreeUri(uri)
                    || !DocumentsContract.isDocumentUri(this, uri)) return false;
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private void openResult(Uri uri, String type) {
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type == null ? "*/*" : type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            notice("No app is installed to open this file type.");
        } catch (SecurityException error) {
            notice("This file is no longer accessible here. Check the folder where you saved it.");
        }
    }

    void makeOutput(String filename, String mime, Exporter builder) {
        work("Preparing export…", () -> {
            File temp = File.createTempFile("export-", ".tmp", getCacheDir());
            try {
                pendingMessage = builder.write(temp);
                if (temp.length() == 0) throw new IOException("No output was produced.");
                return temp;
            } catch (IOException | RuntimeException error) {
                temp.delete();
                throw error;
            }
        }, file -> {
            pending = file;
            pendingMime = mime;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime).putExtra(Intent.EXTRA_TITLE, filename);
            try {
                startActivityForResult(intent, SAVE_AS);
            } catch (ActivityNotFoundException error) {
                file.delete();
                pending = null;
                pendingMime = null;
                pendingMessage = null;
                notice("The Android save dialog is unavailable.");
            }
        });
    }

    static void transfer(InputStream source, OutputStream destination, long limit) throws IOException {
        if (source == null) throw new IOException("Could not open the selected file.");
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = source.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("Choose a PDF smaller than 250 MB.");
            destination.write(buffer, 0, count);
        }
    }

    <T> void work(String title, Callable<T> action, Consumer<T> onDone) {
        work(title, action, onDone, error -> notice(error instanceof IOException ? error.getMessage()
                : "Could not finish this operation. Check the input and available space."));
    }

    <T> void work(String title, Callable<T> action, Consumer<T> onDone, Consumer<Exception> onFail) {
        if (busy) return;
        busy = true;
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(24), dp(19), dp(17), dp(19));
        row.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(29), dp(29)));
        TextView text = text(title, 15, Ui.INK, true);
        text.setPadding(dp(13), 0, 0, 0);
        row.addView(text);
        AlertDialog progress = new AlertDialog.Builder(this).setView(row).setCancelable(false).show();
        background.execute(() -> {
            try {
                T result = action.call();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    busy = false;
                    progress.dismiss();
                    onDone.accept(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    busy = false;
                    progress.dismiss();
                    onFail.accept(error);
                });
            }
        });
    }

    void notice(String message) {
        new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
    }

    TextView text(String content, int size, int color, boolean bold) {
        return Ui.text(this, content, size, color, bold);
    }

    TextView primary(String title, Runnable action) { return Ui.button(this, title, Ui.GREEN, Ui.WHITE, action); }
    TextView neutral(String title, Runnable action) { return Ui.button(this, title, Ui.WHITE, Ui.INK, action); }
    int dp(int size) { return Ui.dp(this, size); }

    @Override public void onBackPressed() {
        if (busy) return;
        if (!"home".equals(screen)) home();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (activePreview != null) activePreview.release();
        background.shutdown();
        super.onDestroy();
    }
}
