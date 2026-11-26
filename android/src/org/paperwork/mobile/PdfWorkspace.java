package org.paperwork.mobile;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PdfWorkspace {
    private final MainActivity app;
    private final List<PdfTools.Input> sources = new ArrayList<>();
    private final ArrayDeque<Uri> queue = new ArrayDeque<>();
    private final List<int[]> order = new ArrayList<>();
    int tool = -1;
    private int page, selectedFile, markedPage;
    private float[] marked;
    private boolean allPages;
    private String editText = "";
    private int editKind;
    private String key;
    private File stamp;

    PdfWorkspace(MainActivity app) { this.app = app; }

    void start(int toolIndex) {
        if (toolIndex != tool) {
            for (PdfTools.Input previous : sources) previous.file.delete();
            sources.clear();
            tool = toolIndex;
            page = selectedFile = markedPage = editKind = 0;
            marked = null;
            allPages = false;
            order.clear();
            editText = "";
            if (stamp != null) stamp.delete();
            stamp = null;
        }
        key = PdfTools.KEYS[toolIndex];
        show();
    }

    private void show() {
        app.screen = "pdf";
        LinearLayout body = app.screen(PdfTools.TITLES[tool], PdfTools.DESCRIPTIONS[tool]);
        boolean merge = "merge".equals(key), organize = "organize".equals(key);
        body.addView(app.primary(merge ? "Add PDFs" : sources.isEmpty() ? "Choose PDF" : "Change PDF",
                () -> app.pick("application/pdf", merge, MainActivity.PICK_PDF)));
        Ui.gap(body, 11);
        if (sources.isEmpty()) {
            app.empty(body, key, merge ? "Add at least two PDFs" : "Choose a PDF",
                    "Pick a file from your device to get started.");
            return;
        }

        if (merge) {
            renderMerger(body);
            return;
        }

        PdfTools.Input source = sources.get(0);
        body.addView(app.text(source.name + " · " + source.count + (source.count == 1 ? " page" : " pages"),
                13, Ui.SOFT, true));
        Ui.gap(body, 9);

        boolean visual = "crop".equals(key) || "edit".equals(key);
        if (visual || organize) {
            int[] row = organize ? order.get(page) : new int[]{page, 0};
            int count = organize ? order.size() : source.count;
            int label = row[0] + 1;
            PagePreview view = app.preview(body, visual ? PagePreview.BOX : PagePreview.READ, box -> {
                marked = box;
                markedPage = page;
            });
            float[] pageBox = marked != null && (page == markedPage || allPages) ? marked : null;
            app.loadPreview(view, () -> {
                if (row[0] >= 0) return PdfTools.preview(source, row[0], row[1]);
                Bitmap blank = Bitmap.createBitmap(420, 594, Bitmap.Config.ARGB_8888);
                blank.eraseColor(Color.WHITE);
                return blank;
            }, pageBox);
            Ui.gap(body, 8);
            LinearLayout pager = Ui.row(app);
            Ui.weight(pager, app.neutral("Previous", () -> { page--; show(); }));
            TextView position = app.text((page + 1) + " / " + count, 14, Ui.INK, true);
            position.setGravity(android.view.Gravity.CENTER);
            Ui.weight(pager, position);
            Ui.weight(pager, app.neutral("Next", () -> { page++; show(); }));
            pager.getChildAt(0).setEnabled(page > 0);
            pager.getChildAt(2).setEnabled(page < count - 1);
            body.addView(pager);
            Ui.gap(body, 8);
            if (organize) {
                body.addView(app.text((label == 0 ? "Blank page" : "Source page " + label)
                        + (row[1] == 0 ? "" : " · rotated " + row[1] + "°"), 13, Ui.SOFT, false));
                Ui.gap(body, 8);
                LinearLayout first = Ui.row(app);
                Ui.weight(first, app.neutral("Move earlier", () -> move(-1)));
                Ui.weight(first, app.neutral("Move later", () -> move(1)));
                Ui.weight(first, app.neutral("Rotate 90°", () -> {
                    row[1] = (row[1] + 90) % 360;
                    show();
                }));
                first.getChildAt(0).setEnabled(page > 0);
                first.getChildAt(1).setEnabled(page < count - 1);
                body.addView(first);
                Ui.gap(body, 7);
                LinearLayout second = Ui.row(app);
                Ui.weight(second, app.neutral("Duplicate", () -> { order.add(++page, row.clone()); show(); }));
                Ui.weight(second, app.neutral("Blank page", () -> { order.add(++page, new int[]{-1, 0}); show(); }));
                Ui.weight(second, app.neutral("Remove", () -> {
                    if (count == 1) return;
                    order.remove(page);
                    page = Math.min(page, order.size() - 1);
                    show();
                }));
                second.getChildAt(2).setEnabled(count > 1);
                body.addView(second);
                Ui.gap(body, 10);
            } else {
                String instructions = "crop".equals(key)
                        ? "Draw around the area to keep. Hidden content stays in the PDF."
                        : "Draw the rectangle where the annotation should appear.";
                body.addView(app.text(instructions, 13, Ui.SOFT, false));
                if ("crop".equals(key)) {
                    CheckBox every = new CheckBox(app);
                    every.setText("Use the same crop on every page");
                    every.setChecked(allPages);
                    every.setOnCheckedChangeListener((box, checked) -> {
                        allPages = checked;
                        view.selection(marked != null && (markedPage == page || allPages) ? marked : null);
                    });
                    body.addView(every);
                    body.addView(app.neutral("Set crop by percentage", () -> percentDialog(view)));
                } else {
                    body.addView(app.neutral("Set markup area by percentage", () -> percentDialog(view)));
                }
                Ui.gap(body, 9);
            }
        }

        EditText textField = null, rangeField = null, confirmation = null;
        Spinner kind = null, format = null, dpi = null;
        CheckBox archive = null;
        if ("split".equals(key) || "images".equals(key)) {
            rangeField = field(body, "Pages (blank = all)", "1-3, 6, 8-10");
            if ("split".equals(key)) {
                archive = new CheckBox(app);
                archive.setText("Save separate PDFs in a ZIP");
                body.addView(archive);
            } else {
                body.addView(app.text("Image format", 14, Ui.INK, true));
                format = option(body, new String[]{"PNG", "JPEG"}, 0);
                body.addView(app.text("Resolution", 14, Ui.INK, true));
                dpi = option(body, new String[]{"72 DPI", "144 DPI", "216 DPI", "288 DPI"}, 1);
            }
        }
        if ("edit".equals(key)) {
            kind = option(body, new String[]{"Text", "Highlight", "Rectangle", "Image"}, editKind);
            textField = field(body, "Text annotation", "Type a short note");
            textField.setText(editText);
            TextView pickStamp = app.neutral(stamp == null ? "Choose image" : "Replace selected image",
                    () -> app.pick("image/*", false, MainActivity.PICK_STAMP));
            pickStamp.setVisibility(editKind == 3 ? android.view.View.VISIBLE : android.view.View.GONE);
            body.addView(pickStamp);
            EditText note = textField;
            Spinner mode = kind;
            mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                                      int index, long id) {
                    editKind = index;
                    note.setEnabled(index == 0);
                    pickStamp.setVisibility(index == 3 ? android.view.View.VISIBLE : android.view.View.GONE);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
            note.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { editText = s.toString(); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }
        if ("watermark".equals(key)) textField = field(body, "Watermark", "e.g. DRAFT");
        if ("protect".equals(key)) {
            textField = field(body, "Opening password", "Letters, numbers or symbols");
            confirmation = field(body, "Repeat password", "Enter the same password");
            for (EditText value : new EditText[]{textField, confirmation}) {
                value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
        }
        if ("markdown".equals(key)) {
            body.addView(app.text("Text-based PDFs work here. Scanned pages need OCR first.", 14, Ui.SOFT, false));
        }
        if ("unlock".equals(key)) {
            body.addView(app.text("Save a new copy without the PDF’s password protection.", 14, Ui.SOFT, false));
        }

        Ui.gap(body, 11);
        final EditText textInput = textField, pagesInput = rangeField, confirmInput = confirmation;
        final Spinner kindInput = kind, formatInput = format, dpiInput = dpi;
        final CheckBox splitInput = archive;
        body.addView(app.primary("Export", () -> {
            PdfTools.Job job = new PdfTools.Job();
            job.key = key;
            job.inputs.addAll(sources);
            job.page = visual ? markedPage : page;
            job.all = allPages;
            if (marked != null) job.box = new float[]{marked[0], marked[1], marked[4], marked[5]};
            job.order.addAll(order);
            job.ranges = pagesInput == null ? "" : pagesInput.getText().toString();
            job.separate = splitInput != null && splitInput.isChecked();
            if (textInput != null) job.text = textInput.getText().toString();
            if (kindInput != null) job.kind = kindInput.getSelectedItem().toString();
            if ("Image".equals(job.kind)) job.stamp = stamp;
            if (dpiInput != null) job.dpi = (dpiInput.getSelectedItemPosition() + 1) * 72;
            job.jpeg = formatInput != null && formatInput.getSelectedItemPosition() == 1;
            if ("protect".equals(key) && !job.text.equals(confirmInput.getText().toString())) {
                app.notice("The passwords must match.");
            } else if (visual && marked == null) {
                app.notice("Select the area on the PDF page first.");
            } else if ("Image".equals(job.kind) && job.stamp == null) {
                app.notice("Choose the image to place.");
            } else if (("edit".equals(key) && "Text".equals(job.kind) || "watermark".equals(key)) && job.text.trim().isEmpty()) {
                app.notice("Enter text for this annotation.");
            } else export(job);
        }));
    }

    private void renderMerger(LinearLayout screen) {
        for (int i = 0; i < sources.size(); i++) {
            int index = i;
            PdfTools.Input item = sources.get(i);
            screen.addView(Ui.button(app, (i + 1) + ". " + item.name + "  ·  " + item.count + " pages",
                    index == selectedFile ? Ui.GREEN : Ui.WHITE, index == selectedFile ? Ui.WHITE : Ui.INK,
                    () -> { selectedFile = index; show(); }));
            Ui.gap(screen, 5);
        }
        LinearLayout controls = Ui.row(app);
        Ui.weight(controls, app.neutral("Move earlier", () -> movePdf(-1)));
        Ui.weight(controls, app.neutral("Move later", () -> movePdf(1)));
        Ui.weight(controls, app.neutral("Remove", () -> {
            sources.remove(selectedFile).file.delete();
            selectedFile = Math.max(0, Math.min(selectedFile, sources.size() - 1));
            show();
        }));
        controls.getChildAt(0).setEnabled(selectedFile > 0);
        controls.getChildAt(1).setEnabled(selectedFile < sources.size() - 1);
        screen.addView(controls);
        Ui.gap(screen, 10);
        TextView merge = app.primary("Merge PDFs", () -> {
            PdfTools.Job job = new PdfTools.Job();
            job.key = key;
            job.inputs.addAll(sources);
            export(job);
        });
        merge.setEnabled(sources.size() >= 2);
        screen.addView(merge);
    }

    private void percentDialog(PagePreview view) {
        LinearLayout form = Ui.column(app);
        form.setPadding(app.dp(18), 0, app.dp(18), 0);
        EditText[] edits = new EditText[4];
        String[] labels = {"Left", "Top", "Right", "Bottom"};
        for (int i = 0; i < edits.length; i++) {
            edits[i] = field(form, labels[i] + " (%)", "");
            edits[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            edits[i].setText(i < 2 ? "10" : "90");
        }
        new AlertDialog.Builder(app).setTitle("Set selection").setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, action) -> {
                    try {
                        float[] value = new float[4];
                        for (int i = 0; i < value.length; i++) value[i] = Integer.parseInt(edits[i].getText().toString()) / 100f;
                        if (!(value[0] >= 0 && value[1] >= 0 && value[2] <= 1 && value[3] <= 1
                                && value[0] < value[2] && value[1] < value[3])) throw new NumberFormatException();
                        marked = PagePreview.rectangle(value[0], value[1], value[2], value[3]);
                        markedPage = page;
                        view.selection(marked);
                    } catch (NumberFormatException error) {
                        app.notice("Use whole percentages from 0 to 100. Right and bottom must exceed left and top.");
                    }
                }).show();
    }

    private void move(int offset) {
        int next = page + offset;
        if (next >= 0 && next < order.size()) {
            Collections.swap(order, page, next);
            page = next;
            show();
        }
    }

    private void movePdf(int offset) {
        int next = selectedFile + offset;
        if (next >= 0 && next < sources.size()) {
            Collections.swap(sources, selectedFile, next);
            selectedFile = next;
            show();
        }
    }

    private EditText field(LinearLayout form, String label, String hint) {
        form.addView(app.text(label, 14, Ui.INK, true));
        EditText input = new EditText(app);
        input.setSingleLine(true);
        input.setContentDescription(label);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.SOFT);
        input.setHint(hint);
        input.setTextSize(15);
        input.setBackground(Ui.shape(Ui.WHITE, Ui.BORDER, 10, app));
        input.setPadding(app.dp(10), 0, app.dp(10), 0);
        form.addView(input, new LinearLayout.LayoutParams(-1, app.dp(48)));
        Ui.gap(form, 9);
        return input;
    }

    private Spinner option(LinearLayout form, String[] options, int selected) {
        Spinner input = new Spinner(app);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(app, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        input.setAdapter(adapter);
        input.setSelection(selected);
        form.addView(input, new LinearLayout.LayoutParams(-1, app.dp(44)));
        return input;
    }

    private void export(PdfTools.Job job) {
        String ext = job.extension();
        String mime = ".md".equals(ext) ? "text/markdown" : ".zip".equals(ext) ? "application/zip" : "application/pdf";
        String original = job.inputs.get(0).name.replaceFirst("(?i)\\.pdf$", "");
        original = original.replaceAll("[\\p{Cntrl}/\\\\]", "_");
        app.makeOutput(original + "-" + job.key + ext, mime, output -> PdfTools.export(job, output));
    }

    void add(List<Uri> inputs) {
        queue.clear();
        queue.addAll("merge".equals(key) ? inputs : inputs.subList(0, 1));
        nextImport();
    }

    void addStamp(Uri source) {
        if (!"edit".equals(key)) return;
        app.work("Opening image…", () -> {
            Bitmap photo = ScanImage.read(app.getContentResolver(), source, 1600);
            File output = null;
            try {
                output = File.createTempFile("stamp-", ".png", app.getCacheDir());
                try (OutputStream stream = Files.newOutputStream(output.toPath())) {
                    if (!photo.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Could not encode the image.");
                }
                return output;
            } catch (IOException | RuntimeException error) {
                if (output != null) output.delete();
                throw error;
            } finally {
                photo.recycle();
            }
        }, file -> {
            if (stamp != null) stamp.delete();
            stamp = file;
            editKind = 3;
            show();
        });
    }

    private void nextImport() {
        Uri source = queue.poll();
        if (source == null) {
            show();
            return;
        }
        app.work("Opening PDF…", () -> {
            File target = File.createTempFile("input-", ".pdf", app.getCacheDir());
            try (InputStream in = app.getContentResolver().openInputStream(source);
                 OutputStream out = Files.newOutputStream(target.toPath())) {
                MainActivity.transfer(in, out, 250_000_000L);
                return target;
            } catch (IOException | RuntimeException error) {
                target.delete();
                throw error;
            }
        }, file -> {
            String name = "Document.pdf";
            try (android.database.Cursor cursor = app.getContentResolver()
                    .query(source, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
            } catch (RuntimeException ignored) {}
            inspect(file, name, "");
        }, error -> {
            app.notice(error instanceof IOException ? error.getMessage() : "Could not read this PDF.");
            nextImport();
        });
    }

    private void inspect(File file, String name, String password) {
        app.work("Reading PDF pages…", () -> PdfTools.inspect(file, password), count -> {
            if (!"merge".equals(key)) {
                for (PdfTools.Input old : sources) old.file.delete();
                sources.clear();
                order.clear();
                page = 0;
                marked = null;
                allPages = false;
                if ("organize".equals(key)) {
                    for (int i = 0; i < count; i++) order.add(new int[]{i, 0});
                }
            }
            sources.add(new PdfTools.Input(file, name, password, count));
            selectedFile = sources.size() - 1;
            nextImport();
        }, error -> {
            if (error instanceof PdfTools.Locked) askPassword(file, name, !password.isEmpty());
            else {
                file.delete();
                app.notice("Could not read " + name + " as a PDF.");
                nextImport();
            }
        });
    }

    private void askPassword(File file, String name, boolean retry) {
        LinearLayout layout = Ui.column(app);
        layout.setPadding(app.dp(18), 0, app.dp(18), 0);
        EditText field = field(layout, retry ? "Try the PDF password again" : "PDF password", "");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(app).setTitle(name).setView(layout)
                .setPositiveButton("Open", (d, action) -> inspect(file, name, field.getText().toString()))
                .setNegativeButton("Cancel", (d, action) -> { file.delete(); nextImport(); }).create();
        dialog.setOnCancelListener(d -> { file.delete(); nextImport(); });
        dialog.show();
    }
}
