"""File selection, page preview and settings for the PDF tools."""

import json
import sys
from functools import partial
from pathlib import Path

from PyQt5.QtCore import QProcess, QPointF, QRectF, QSize, Qt, QUrl, pyqtSignal
from PyQt5.QtGui import QColor, QDesktopServices, QImage, QPainter, QPen
from PyQt5.QtWidgets import (
    QAbstractItemView, QCheckBox, QColorDialog, QComboBox, QDialog, QDialogButtonBox,
    QFileDialog, QFormLayout, QFrame, QHBoxLayout, QInputDialog, QLabel, QLineEdit,
    QListWidget, QListWidgetItem, QMessageBox, QPlainTextEdit, QProgressBar,
    QSizePolicy, QSpinBox, QStackedWidget, QVBoxLayout, QWidget,
)

from paperwork.design import GREEN, button, icon, label
from paperwork.engine import InputError, PasswordRequired, apply_edit
from paperwork.engine import open_image, open_pdf, page_groups, pdf, pixmap, validate_password

IMAGE_FILTER = "Images (*.png *.jpg *.jpeg *.bmp *.tif *.tiff *.gif)"
SELECT = Qt.UserRole


class Preview(QWidget):
    area_drawn = pyqtSignal(tuple)

    def __init__(self, selectable):
        super().__init__()
        self.selectable = selectable
        self.image = QImage()
        self.paper = QRectF()
        self.box = ()
        self.anchor = None
        self.tip = None
        self.setMinimumSize(290, 250)
        self.setAccessibleName("PDF page preview")
        if selectable:
            self.setCursor(Qt.CrossCursor)

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.SmoothPixmapTransform)
        if self.image.isNull():
            painter.setPen(QColor("#506c60"))
            painter.drawText(self.rect(), Qt.AlignCenter, "Page preview unavailable")
            return
        scaled = self.image.size().scaled(self.size() - QSize(32, 32), Qt.KeepAspectRatio)
        self.paper = QRectF((self.width() - scaled.width()) / 2, (self.height() - scaled.height()) / 2,
                            scaled.width(), scaled.height())
        painter.fillRect(self.paper.translated(2, 3), QColor("#bfd0c5"))
        painter.drawImage(self.paper, self.image)
        if self.anchor is not None:
            area = QRectF(self.anchor, self.tip).normalized()
        elif self.box:
            x0, y0, x1, y1 = self.box
            area = QRectF(self.paper.x() + x0 * self.paper.width(), self.paper.y() + y0 * self.paper.height(),
                          (x1 - x0) * self.paper.width(), (y1 - y0) * self.paper.height())
        else:
            return
        painter.fillRect(area, QColor(110, 186, 126, 46))
        painter.setPen(QPen(QColor("#23734f"), 2, Qt.DashLine))
        painter.drawRect(area)

    def mousePressEvent(self, event):
        if (self.selectable and event.button() == Qt.LeftButton and not self.image.isNull()
                and self.paper.contains(event.localPos())):
            self.anchor = self.tip = event.localPos()
            self.update()

    def mouseMoveEvent(self, event):
        if self.anchor is not None:
            point = event.localPos()
            self.tip = QPointF(max(self.paper.left(), min(self.paper.right(), point.x())),
                               max(self.paper.top(), min(self.paper.bottom(), point.y())))
            self.update()

    def mouseReleaseEvent(self, event):
        if self.anchor is None:
            return
        self.mouseMoveEvent(event)
        area = QRectF(self.anchor, self.tip).normalized()
        self.anchor = self.tip = None
        if min(area.width(), area.height()) >= 4:
            self.area_drawn.emit(tuple(
                min(1, max(0, value))
                for value in ((area.left() - self.paper.left()) / self.paper.width(),
                              (area.top() - self.paper.top()) / self.paper.height(),
                              (area.right() - self.paper.left()) / self.paper.width(),
                              (area.bottom() - self.paper.top()) / self.paper.height())
            ))
        self.update()


class Workspace(QWidget):
    def __init__(self, key, detail, go_back):
        super().__init__(objectName="main")
        self.key = key
        self.description = detail[1]
        self.title = detail[0]
        self.multi = key in {"merge", "images-pdf"}
        self.setAcceptDrops(True)
        self.sources = []
        self.fields = {}
        self.doc = None
        self.index = 0
        self.crop = ()
        self.crop_index = 0
        self.edits = []
        self.color = QColor("#24574b")
        self.stamp = None
        self.last_saved = None
        self.worker = None
        self.dirty = False

        outer = QVBoxLayout(self)
        outer.setContentsMargins(29, 19, 29, 17)
        outer.setSpacing(12)
        heading = QHBoxLayout()
        heading.addWidget(button("Tool library", go_back, glyph="left"))
        heading.addSpacing(2)
        heading.addWidget(label(self.title, "heading"))
        heading.addStretch()
        self.change = button("Add files" if self.multi else "Change file", self.pick, glyph="open")
        self.change.hide()
        heading.addWidget(self.change)
        outer.addLayout(heading)
        outer.addWidget(label(self.description, "muted", True))

        self.contents = QStackedWidget()
        self.contents.addWidget(self.drop_zone())
        self.contents.addWidget(self.editor())
        outer.addWidget(self.contents, 1)

        footer = QHBoxLayout()
        self.status = label("", "muted", True)
        self.status.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        footer.addWidget(self.status, 1)
        self.open_result = button("Open output", self.open_output, glyph="right")
        self.open_result.hide()
        footer.addWidget(self.open_result)
        self.save = button("Export", self.export, style="primary")
        self.save.setFixedHeight(39)
        self.save.setMinimumWidth(119)
        self.save.setEnabled(False)
        footer.addWidget(self.save)
        outer.addLayout(footer)
        self.progress = QProgressBar()
        self.progress.setRange(0, 0)
        self.progress.setTextVisible(False)
        self.progress.hide()
        outer.addWidget(self.progress)

    def drop_zone(self):
        zone = QFrame(objectName="dropZone")
        box = QVBoxLayout(zone)
        box.setContentsMargins(20, 20, 20, 20)
        box.setSpacing(11)
        box.addStretch()
        badge = QLabel()
        badge.setPixmap(icon(self.key, GREEN, 43).pixmap(43, 43))
        box.addWidget(badge, alignment=Qt.AlignHCenter)
        noun = "images" if self.key == "images-pdf" else ("PDFs" if self.multi else "a PDF")
        box.addWidget(label(f"Drop {noun} here", "section"), alignment=Qt.AlignHCenter)
        select = button(f"Choose {noun}", self.pick, style="primary", glyph="open")
        select.setMinimumHeight(40)
        box.addWidget(select, alignment=Qt.AlignHCenter)
        box.addStretch()
        return zone

    def editor(self):
        editor = QWidget()
        row = QHBoxLayout(editor)
        row.setSpacing(13)
        row.setContentsMargins(0, 0, 0, 0)
        panel = QFrame(objectName="previewFrame")
        view = QVBoxLayout(panel)
        view.setContentsMargins(10, 9, 10, 9)
        self.preview = Preview(self.key in {"crop", "edit"})
        self.preview.area_drawn.connect(self.select_area)
        view.addWidget(self.preview, 1)
        controls = QHBoxLayout()
        controls.setSpacing(6)
        controls.addStretch()
        self.prev = button("Previous", partial(self.turn_page, -1), glyph="left")
        self.page = label("", "small")
        self.next = button("Next", partial(self.turn_page, 1), glyph="right")
        for control in (self.prev, self.page, self.next):
            controls.addWidget(control)
        controls.addStretch()
        view.addLayout(controls)
        row.addWidget(panel, 1)

        options = QFrame(objectName="surface")
        options.setFixedWidth(302)
        column = QVBoxLayout(options)
        column.setContentsMargins(14, 13, 14, 13)
        column.setSpacing(9)
        self.file_info = label("", "small", True)
        self.file_info.setWordWrap(False)
        self.file_info.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.file_info.setSizePolicy(QSizePolicy.Ignored, QSizePolicy.Fixed)
        column.addWidget(label("Files" if self.multi else "Settings", "subheading"))
        column.addWidget(self.file_info)
        self.list = None
        if self.multi or self.key == "organize":
            self.list = QListWidget()
            self.list.setAccessibleName("Input files" if self.multi else "Page order")
            self.list.setSelectionMode(QAbstractItemView.SingleSelection)
            self.list.setDragDropMode(QAbstractItemView.InternalMove)
            self.list.setDefaultDropAction(Qt.MoveAction)
            self.list.currentRowChanged.connect(self.refresh_preview)
            self.list.model().rowsMoved.connect(self.reordered)
            column.addWidget(self.list, 1)
            first = QHBoxLayout()
            first.addWidget(button("Move up", partial(self.move_row, -1)))
            first.addWidget(button("Move down", partial(self.move_row, 1)))
            first.addWidget(button("Remove", self.remove_row))
            column.addLayout(first)
            if self.key == "organize":
                second = QHBoxLayout()
                second.addWidget(button("Rotate", self.rotate_row))
                second.addWidget(button("Duplicate", self.duplicate_row))
                second.addWidget(button("Blank page", self.blank_row))
                column.addLayout(second)
            column.addWidget(label("Drag rows to change the output order.", "small", True))

        form = QFormLayout()
        form.setFieldGrowthPolicy(QFormLayout.AllNonFixedFieldsGrow)
        form.setVerticalSpacing(8)
        if self.key == "split":
            mode = self.combo(form, "Output", "mode", ["Separate PDFs (ZIP)", "One PDF (extract ranges)"])
            mode.currentTextChanged.connect(self.update_hint)
            self.line(form, "Pages", "pages", "e.g. 1-3, 6, 8-10")
        elif self.key == "compress":
            level = self.combo(form, "Quality", "level", ["Balanced", "Smallest", "Lossless"])
            level.currentTextChanged.connect(self.update_hint)
        elif self.key == "images-pdf":
            paper = self.combo(form, "Page size", "paper", ["Fit to image", "A4 portrait"])
            margin = self.spin(form, "Margin", "margin", 0, 100, 18, " pt")
            margin.setEnabled(False)
            paper.currentTextChanged.connect(lambda size: margin.setEnabled(size == "A4 portrait"))
        elif self.key == "pdf-images":
            self.combo(form, "Format", "format", ["PNG", "JPEG"])
            self.spin(form, "Resolution", "dpi", 72, 300, 144, " DPI")
            self.line(form, "Pages", "pages", "All pages")
        elif self.key == "crop":
            self.fields["all"] = QCheckBox("Apply to all pages")
            self.fields["all"].toggled.connect(self.update_crop)
            form.addRow(self.fields["all"])
        elif self.key == "edit":
            mode = self.combo(form, "Add", "edit_type", ["Text", "Highlight", "Rectangle", "Image"])
            mode.currentTextChanged.connect(self.edit_type_changed)
            self.edit_text = QPlainTextEdit()
            self.edit_text.setAccessibleName("Text to place")
            self.edit_text.setPlaceholderText("Text to place…")
            self.edit_text.setFixedHeight(66)
            form.addRow(self.edit_text)
            edit_size = self.spin(form, "Font size", "edit_size", 6, 96, 18, " pt")
            self.size_label = form.labelForField(edit_size)
            self.color_button = button("Choose color", self.choose_color)
            form.addRow(self.color_button)
            self.image_button = button("Choose image", self.choose_image, glyph="open")
            self.image_button.hide()
            form.addRow(self.image_button)
        elif self.key == "watermark":
            self.line(form, "Text", "text", "e.g. DRAFT")
            self.spin(form, "Size", "size", 8, 96, 32, " pt")
            self.spin(form, "Opacity", "opacity", 5, 100, 25, "%")
        elif self.key == "numbers":
            self.combo(form, "Position", "position", ["Center", "Left", "Right"])
            self.spin(form, "Start at", "start", 1, 1_000_000, 1)
        elif self.key == "protect":
            secret = self.line(form, "Password", "secret", "Enter password")
            confirm = self.line(form, "Repeat", "confirm", "Repeat password")
            for field in (secret, confirm):
                field.setEchoMode(QLineEdit.Password)
                field.setMaxLength(40)
        column.addLayout(form)

        if self.key in {"edit", "crop"}:
            column.addWidget(button("Set area by percentage…", self.enter_area))
            if self.key == "edit":
                self.undo = button("Undo last edit", self.undo_edit)
                self.undo.setEnabled(False)
                column.addWidget(self.undo)
                self.edit_count = label("0 edits", "small")
                column.addWidget(self.edit_count)
            else:
                column.addWidget(button("Clear crop area", self.clear_crop))
        self.hint = label("", "muted", True)
        column.addWidget(self.hint)
        self.update_hint()
        if self.list is None:
            column.addStretch()
        row.addWidget(options)
        return editor

    def line(self, form, title, key, placeholder):
        field = QLineEdit()
        field.setPlaceholderText(placeholder)
        self.fields[key] = field
        form.addRow(title, field)
        return field

    def combo(self, form, title, key, values):
        field = QComboBox()
        field.addItems(values)
        self.fields[key] = field
        form.addRow(title, field)
        return field

    def spin(self, form, title, key, minimum, maximum, value, suffix=""):
        field = QSpinBox()
        field.setRange(minimum, maximum)
        field.setValue(value)
        field.setSuffix(suffix)
        self.fields[key] = field
        form.addRow(title, field)
        return field

    def update_hint(self):
        hints = {
            "merge": "Add at least two PDFs. The new file follows the list order.",
            "crop": "Draw the area to keep. Cropping hides margins without removing their content.",
            "edit": "Draw a box on the page to place the selected text, image or markup.",
            "markdown": "Exports selectable text, with estimated headings and lists. Scanned pages need OCR first.",
            "pdf-images": "Each selected page is rendered as an image in a ZIP archive.",
            "watermark": "The text stamp is centered on every page.",
            "numbers": "Page numbers are placed along the bottom edge.",
            "protect": "The output requires this password to open. Store it somewhere safe.",
            "unlock": "Open the source with its password, then export an unencrypted copy.",
        }
        if self.key == "split":
            hints[self.key] = ("Each range creates a PDF. Leave Pages blank to create one PDF per page."
                               if self.fields["mode"].currentIndex() == 0
                               else "Combine the selected ranges in the order entered.")
        elif self.key == "compress":
            level = self.fields["level"].currentText()
            hints[self.key] = {"Lossless": "Optimize PDF objects while preserving image quality.",
                               "Balanced": "Downsample high-resolution images to about 150 DPI.",
                               "Smallest": "Downsample high-resolution images to about 100 DPI."}[level]
        self.hint.setText(hints.get(self.key, ""))
        self.hint.setVisible(self.key in hints)

    @staticmethod
    def pick_files(parent, key):
        file_filter = IMAGE_FILTER if key == "images-pdf" else "PDF documents (*.pdf)"
        dialog = QFileDialog.getOpenFileNames if key in {"merge", "images-pdf"} else QFileDialog.getOpenFileName
        result, _ = dialog(parent, "Choose input files", "", file_filter)
        return [result] if isinstance(result, str) and result else result or []

    def pick(self):
        paths = self.pick_files(self, self.key)
        if paths:
            self.add_paths(paths)

    def dragEnterEvent(self, event):
        if not self.worker and any(url.isLocalFile() for url in event.mimeData().urls()):
            event.acceptProposedAction()

    def dropEvent(self, event):
        paths = [url.toLocalFile() for url in event.mimeData().urls() if url.isLocalFile()]
        if paths and not self.worker:
            self.add_paths(paths if self.multi else paths[:1])
            event.acceptProposedAction()

    def load_source(self, path):
        if not Path(path).is_file():
            raise InputError("Choose a regular file.")
        if self.key == "images-pdf":
            return open_image(path), ""
        password = ""
        while True:
            try:
                return open_pdf(path, password), password
            except PasswordRequired:
                password, accepted = QInputDialog.getText(
                    self, "PDF password", f"Enter the password for {Path(path).name}",
                    QLineEdit.Password)
                if not accepted:
                    return None, ""

    def add_paths(self, paths):
        if self.worker or (not self.multi and not self.can_leave()):
            return
        errors = []
        added = False
        for path in paths[:1] if not self.multi else paths:
            try:
                doc, password = self.load_source(path)
                if doc is None:
                    continue
                if self.multi:
                    item = QListWidgetItem(icon("pdf", GREEN, 17), Path(path).name)
                    item.setToolTip(path)
                    item.setData(SELECT, (path, password))
                    self.list.addItem(item)
                    doc.close()
                    self.list.setCurrentItem(item)
                else:
                    if self.doc:
                        self.doc.close()
                    self.doc = doc
                    self.sources = [(path, password)]
                    self.index = 0
                    self.crop = ()
                    self.edits.clear()
                    self.dirty = False
                    if self.key == "organize":
                        self.list.blockSignals(True)
                        self.list.clear()
                        for number in range(doc.page_count):
                            self.add_row(number)
                        self.list.blockSignals(False)
                        self.list.setCurrentRow(0)
                    elif self.key == "edit":
                        self.update_edits()
                added = True
            except InputError as error:
                errors.append(f"{Path(path).name} — {error}")
            except (OSError, RuntimeError, ValueError):
                errors.append(f"{Path(path).name} — could not read this file.")
        if added:
            self.open_result.hide()
            self.last_saved = None
            self.status.clear()
            self.sync_files()
        if errors:
            self.message("Could not open input", "\n".join(errors), warning=True)

    def sync_files(self):
        count = self.list.count() if self.multi else len(self.sources)
        self.contents.setCurrentIndex(1 if count else 0)
        self.change.setVisible(bool(count))
        self.save.setEnabled(count >= (2 if self.key == "merge" else 1))
        if self.multi:
            self.file_info.setText(f"{count} {'files' if count != 1 else 'file'}")
        elif count:
            path = self.sources[0][0]
            pages = self.doc.page_count
            self.file_info.setText(f"{pages} {'pages' if pages != 1 else 'page'} · {Path(path).name}")
            self.file_info.setToolTip(path)
        self.refresh_preview()

    def selected_row(self):
        return self.list.currentRow() if self.list else -1

    def add_row(self, index, angle=0, position=None):
        title = "Blank page" if index == -1 else f"Page {index + 1}"
        item = QListWidgetItem(icon("pdf", GREEN, 17), f"{title}   ·   {angle}°" if angle else title)
        item.setData(SELECT, (index, angle))
        self.list.insertItem(self.list.count() if position is None else position, item)
        return item

    def move_row(self, offset):
        row = self.selected_row()
        place = row + offset
        if row < 0 or not 0 <= place < self.list.count():
            return
        item = self.list.takeItem(row)
        self.list.insertItem(place, item)
        self.list.setCurrentItem(item)
        self.reordered()

    def reordered(self, *_):
        self.dirty = True
        self.refresh_preview()

    def remove_row(self):
        row = self.selected_row()
        if row < 0:
            return
        if self.key == "organize" and self.list.count() == 1:
            self.status.setText("Keep at least one page.")
            return
        self.list.takeItem(row)
        self.dirty = self.key == "organize" or self.list.count() > 0
        self.sync_files()

    def rotate_row(self):
        row = self.selected_row()
        if row < 0:
            return
        index, rotation = self.list.takeItem(row).data(SELECT)
        self.list.setCurrentItem(self.add_row(index, (rotation + 90) % 360, row))
        self.reordered()

    def duplicate_row(self):
        row = self.selected_row()
        if row < 0:
            return
        index, rotation = self.list.item(row).data(SELECT)
        self.list.setCurrentItem(self.add_row(index, rotation, row + 1))
        self.reordered()

    def blank_row(self):
        self.list.setCurrentItem(self.add_row(-1, position=self.selected_row() + 1))
        self.reordered()

    def turn_page(self, step):
        if self.multi or self.key == "organize":
            self.list.setCurrentRow(self.selected_row() + step)
        elif self.doc and 0 <= self.index + step < self.doc.page_count:
            self.index += step
            self.refresh_preview()

    def display_page(self, page, rotation=0):
        bounds = page.rect
        scale = min(2.0, 1500 / max(1, bounds.width, bounds.height))
        image = pixmap(page, scale, rotation=rotation)
        self.preview.image = QImage(image.samples, image.width, image.height, image.stride,
                                    QImage.Format_RGB888).copy()

    def refresh_preview(self, *_):
        self.preview.image = QImage()
        self.preview.box = self.visible_crop()
        index = self.index
        count = self.doc.page_count if self.doc else 0
        prefix = "Page"
        try:
            if self.multi:
                prefix = "File"
                index = self.selected_row()
                count = self.list.count()
                if index >= 0:
                    path, password = self.list.item(index).data(SELECT)
                    source = open_image(path) if self.key == "images-pdf" else open_pdf(path, password)
                    with source:
                        self.display_page(source[0])
            elif self.key == "organize":
                index = self.selected_row()
                count = self.list.count()
                if index >= 0:
                    page, angle = self.list.item(index).data(SELECT)
                    if page < 0:
                        with pdf.open() as blank:
                            self.display_page(blank.new_page(), angle)
                    else:
                        self.display_page(self.doc[page], angle)
            elif self.doc:
                active = [edit for edit in self.edits if edit["page"] == index]
                if not active:
                    self.display_page(self.doc[index])
                else:
                    with pdf.open() as scratch:
                        scratch.insert_pdf(self.doc, from_page=index, to_page=index)
                        for edit in active:
                            apply_edit(scratch[0], edit)
                        self.display_page(scratch[0])
        except (OSError, RuntimeError, ValueError):
            self.status.setText("This page could not be previewed.")
        self.page.setText(f"{prefix} {index + 1} of {count}" if count and index >= 0 else "")
        self.prev.setEnabled(index > 0)
        self.next.setEnabled(0 <= index < count - 1)
        self.preview.update()

    def edit_type_changed(self, mode):
        self.edit_text.setVisible(mode == "Text")
        size = self.fields["edit_size"]
        size.setVisible(mode == "Text")
        self.size_label.setVisible(mode == "Text")
        self.color_button.setVisible(mode != "Image")
        self.image_button.setVisible(mode == "Image")
        self.color = QColor("#f2cd37" if mode == "Highlight" else GREEN)
        self.color_button.setText(f"Color  {self.color.name().upper()}")

    def choose_color(self):
        color = QColorDialog.getColor(self.color, self, "Choose markup color")
        if color.isValid():
            self.color = color
            self.color_button.setText(f"Color  {color.name().upper()}")

    def choose_image(self):
        path, _ = QFileDialog.getOpenFileName(self, "Choose an image", "", IMAGE_FILTER)
        if path:
            self.stamp = path
            self.image_button.setText(Path(path).name)
            self.image_button.setToolTip(path)

    def enter_area(self):
        dialog = QDialog(self)
        dialog.setWindowTitle("Set area by percentage")
        layout = QVBoxLayout(dialog)
        layout.addWidget(label("Enter the box edges as percentages of the visible page.", "muted", True))
        form = QFormLayout()
        edges = []
        for title, value in zip(("Left", "Top", "Right", "Bottom"), self.crop or (.10, .10, .90, .90)):
            field = QSpinBox()
            field.setRange(0, 100)
            field.setSuffix("%")
            field.setValue(round(value * 100))
            form.addRow(title, field)
            edges.append(field)
        layout.addLayout(form)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        if dialog.exec_():
            left, top, right, bottom = (field.value() / 100 for field in edges)
            if left >= right or top >= bottom:
                self.status.setText("Right and bottom must be greater than left and top.")
                return
            self.select_area((left, top, right, bottom))

    def select_area(self, box):
        if self.key == "crop":
            self.crop = box
            self.crop_index = self.index
            self.update_crop()
            return
        mode = self.fields["edit_type"].currentText()
        text = self.edit_text.toPlainText().strip()
        if mode == "Text" and not text:
            self.status.setText("Enter text before drawing its box.")
            self.edit_text.setFocus()
            return
        if mode == "Image" and not self.stamp:
            self.status.setText("Choose an image before drawing its box.")
            return
        edit = {"page": self.index, "box": box, "kind": mode, "text": text,
                "size": self.fields["edit_size"].value(), "image": self.stamp,
                "color": self.color.getRgbF()[:3]}
        try:
            with pdf.open() as scratch:
                scratch.insert_pdf(self.doc, from_page=self.index, to_page=self.index)
                apply_edit(scratch[0], edit)
        except (OSError, RuntimeError, ValueError) as error:
            self.status.setText(str(error) if isinstance(error, InputError) else "Could not place this markup.")
            return
        self.edits.append(edit)
        self.dirty = True
        self.status.clear()
        self.update_edits()

    def undo_edit(self):
        if self.edits:
            self.edits.pop()
        self.dirty = bool(self.edits)
        self.update_edits()

    def update_edits(self):
        count = len(self.edits)
        self.edit_count.setText(f"{count} {'edit' if count == 1 else 'edits'}")
        self.undo.setEnabled(bool(count))
        self.refresh_preview()

    def update_crop(self):
        self.dirty = bool(self.crop)
        self.preview.box = self.visible_crop()
        if self.crop:
            scope = "all pages" if self.fields["all"].isChecked() else f"page {self.crop_index + 1}"
            self.status.setText(f"Crop area selected for {scope}.")
        self.preview.update()

    def visible_crop(self):
        if self.crop and (self.crop_index == self.index or self.fields["all"].isChecked()):
            return self.crop
        return ()

    def clear_crop(self):
        self.crop = ()
        self.status.clear()
        self.update_crop()

    def options(self):
        values = {}
        for key, field in self.fields.items():
            if isinstance(field, QComboBox):
                values[key] = field.currentText()
            elif isinstance(field, QSpinBox):
                values[key] = field.value()
            elif isinstance(field, QLineEdit):
                values[key] = field.text()
            elif isinstance(field, QCheckBox):
                values[key] = field.isChecked()

        if self.key in {"split", "pdf-images"}:
            page_groups(values["pages"], self.doc.page_count)
        if self.key == "split" and values["mode"].startswith("One") and not values["pages"].strip():
            raise InputError("Enter at least one page range to extract.")
        if self.key == "pdf-images":
            values["format"] = values["format"].lower()
        if self.key == "organize":
            values["layout"] = self.rows()
        if self.key == "crop":
            if not self.crop:
                raise InputError("Draw the area you want to keep.")
            values.update(box=self.crop, page=self.crop_index)
        if self.key == "edit":
            if not self.edits:
                raise InputError("Add at least one edit before exporting.")
            values["edits"] = self.edits
        if self.key == "protect":
            validate_password(values["secret"])
            if values.pop("confirm") != values["secret"]:
                raise InputError("The passwords do not match.")
        if self.key == "watermark" and not values["text"].strip():
            raise InputError("Enter the watermark text.")
        return values

    def rows(self):
        return [self.list.item(row).data(SELECT) for row in range(self.list.count())]

    def export(self):
        try:
            options = self.options()
        except InputError as error:
            self.status.setText(str(error))
            return
        sources = self.rows() if self.multi else self.sources
        path = Path(sources[0][0])
        extension = "pdf"
        if self.key == "markdown":
            extension = "md"
        elif self.key == "pdf-images" or (self.key == "split" and options["mode"].startswith("Separate")):
            extension = "zip"
        endings = {
            "merge": "merged", "split": "split", "compress": "compressed", "edit": "edited",
            "organize": "organized", "crop": "cropped", "images-pdf": "combined",
            "pdf-images": "images", "markdown": "notes", "watermark": "watermarked",
            "numbers": "numbered", "protect": "protected", "unlock": "unlocked",
        }
        suggested = path.with_name(f"{path.stem}-{endings[self.key]}.{extension}")
        destination, _ = QFileDialog.getSaveFileName(
            self, "Export as", str(suggested),
            {"pdf": "PDF (*.pdf)", "md": "Markdown (*.md)", "zip": "ZIP archive (*.zip)"}[extension])
        if not destination:
            return
        if not destination.lower().endswith("." + extension):
            destination += "." + extension
            if Path(destination).exists() and QMessageBox.question(
                    self, "Replace output?", "The output file already exists. Replace it?",
                    QMessageBox.Yes | QMessageBox.No, QMessageBox.No) != QMessageBox.Yes:
                return
        self.start_export({"tool": self.key, "sources": sources, "destination": destination, "options": options})

    def start_export(self, job):
        if self.worker:
            return
        self.setEnabled(False)
        self.open_result.hide()
        self.progress.show()
        self.status.setText("Exporting locally…")
        process = QProcess(self)
        process.setStandardErrorFile(QProcess.nullDevice())
        self.worker = process
        data = json.dumps(job).encode()
        process.started.connect(lambda: (process.write(data), process.closeWriteChannel()))
        process.finished.connect(partial(self.finish_export, job["destination"]))
        process.errorOccurred.connect(self.export_error)
        process.start(sys.executable, ["-I", str(Path(__file__).with_name("engine.py"))])

    def finish_export(self, destination, code, exit_status):
        if not self.worker:
            return
        if code or exit_status != QProcess.NormalExit:
            response = {"error": "The PDF engine stopped before the file could be saved."}
        else:
            try:
                response = json.loads(bytes(self.worker.readAllStandardOutput()))
            except (UnicodeDecodeError, ValueError):
                response = {"error": "The export returned an unreadable result."}
        self.release_worker()
        if "error" in response:
            self.status.setText(response["error"])
        else:
            self.last_saved = destination
            self.dirty = False
            if self.key == "protect":
                self.fields["secret"].clear()
                self.fields["confirm"].clear()
            self.status.setText(response["message"])
            self.open_result.show()

    def export_error(self, error):
        if error == QProcess.FailedToStart:
            self.release_worker()
            self.status.setText("The background export could not start.")

    def release_worker(self):
        self.worker.deleteLater()
        self.worker = None
        self.setEnabled(True)
        self.progress.hide()

    def open_output(self):
        if self.last_saved and not QDesktopServices.openUrl(QUrl.fromLocalFile(self.last_saved)):
            self.status.setText("Could not open the output with the system's default app.")

    def message(self, title, detail, warning=False):
        box = QMessageBox(self)
        box.setIcon(QMessageBox.Warning if warning else QMessageBox.Information)
        box.setTextFormat(Qt.PlainText)
        box.setWindowTitle(title)
        box.setText(detail)
        box.exec_()

    def can_leave(self):
        if self.worker:
            self.status.setText("Wait for the export to finish.")
            return False
        if not self.dirty:
            return True
        return QMessageBox.question(
            self, "Discard changes?", "This workspace has changes that haven’t been exported. Discard them?",
            QMessageBox.Discard | QMessageBox.Cancel, QMessageBox.Cancel) == QMessageBox.Discard
