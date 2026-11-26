"""Paperwork desktop interface."""

import sys
from functools import partial
from PyQt5.QtCore import QRectF, Qt, QTimer
from PyQt5.QtGui import QKeySequence, QPainter
from PyQt5.QtWidgets import (
    QApplication, QButtonGroup, QFrame, QGridLayout, QHBoxLayout, QLabel,
    QLineEdit, QMainWindow, QPushButton, QScrollArea, QShortcut, QStackedWidget,
    QVBoxLayout, QWidget,
)

from paperwork.design import GREEN, STYLE, button, draw_hero, icon, label
from paperwork.workspace import Workspace


TOOLS = {
    "merge": ("Merge PDF", "Combine files into one PDF in the order you choose.", "Organize"),
    "split": ("Split PDF", "Extract page ranges or save separate PDFs.", "Organize"),
    "compress": ("Compress PDF", "Shrink PDFs with your choice of quality.", "Optimize"),
    "edit": ("Edit PDF", "Place text, images, highlights and shapes.", "Edit"),
    "organize": ("Organize pages", "Reorder, rotate, duplicate or remove pages.", "Organize"),
    "crop": ("Crop PDF", "Select the area of the page you want to keep.", "Edit"),
    "images-pdf": ("Images to PDF", "Combine JPG, PNG and other images into a PDF.", "Convert"),
    "pdf-images": ("PDF to images", "Export pages as high-resolution PNG or JPG.", "Convert"),
    "markdown": ("PDF to Markdown", "Turn selectable PDF text into editable notes.", "Convert"),
    "watermark": ("Watermark", "Apply a custom text stamp across your pages.", "Edit"),
    "numbers": ("Page numbers", "Add numbered footers to every page.", "Edit"),
    "protect": ("Protect PDF", "Encrypt a PDF with an opening password.", "Secure"),
    "unlock": ("Unlock PDF", "Remove encryption using the known password.", "Secure"),
}

CATEGORY = {
    "All tools": ("grid", "#e7f2e8", "#32634e"),
    "Organize": ("organize", "#e6f1ea", "#32694e"),
    "Optimize": ("compress", "#f5ebd8", "#8e5e20"),
    "Edit": ("edit", "#eee9f8", "#664c94"),
    "Convert": ("pdf-images", "#e5eff9", "#336593"),
    "Secure": ("shield", "#f5e8ef", "#874663"),
}


def caption(text, style="small"):
    widget = label(text, style)
    return widget


class Hero(QFrame):
    def __init__(self, open_pdf):
        super().__init__()
        self.setFixedHeight(217)
        layout = QHBoxLayout(self)
        layout.setContentsMargins(29, 19, 20, 19)
        left = QVBoxLayout()
        left.setSpacing(10)
        left.addStretch()
        left.addWidget(label("Your PDF workspace", "heroTitle"))
        left.addWidget(label("Edit, convert and organize files on your computer.", "heroCopy"))
        left.addSpacing(5)
        call = button("Open a PDF", open_pdf, style="heroButton", glyph="open")
        call.setFixedHeight(40)
        left.addWidget(call, 0, Qt.AlignLeft)
        left.addStretch()
        layout.addLayout(left)
        layout.addStretch()

    def paintEvent(self, event):
        painter = QPainter(self)
        draw_hero(painter, QRectF(self.rect()))


class ToolCard(QPushButton):
    def __init__(self, key, show_tool):
        title, description, category = TOOLS[key]
        super().__init__()
        self.setObjectName("toolCard")
        self.setCursor(Qt.PointingHandCursor)
        self.setAccessibleName(title)
        self.setAccessibleDescription(description)
        self.setMinimumHeight(121)
        self.clicked.connect(partial(show_tool, key))
        layout = QVBoxLayout(self)
        layout.setContentsMargins(15, 12, 14, 11)
        layout.setSpacing(4)
        top = QHBoxLayout()
        top.setSpacing(0)
        _, color, foreground = CATEGORY[category]
        badge = QLabel()
        badge.setFixedSize(35, 35)
        badge.setAlignment(Qt.AlignCenter)
        badge.setStyleSheet(f"background: {color}; border-radius: 8px;")
        badge.setPixmap(icon(key, foreground, 21).pixmap(21, 21))
        arrow = QLabel()
        arrow.setFixedSize(23, 22)
        arrow.setAlignment(Qt.AlignCenter)
        arrow.setPixmap(icon("right", "#5e7b6d", 14).pixmap(14, 14))
        top.addWidget(badge)
        top.addStretch()
        top.addWidget(arrow)
        layout.addLayout(top)
        layout.addWidget(label(title, "subheading"))
        text = label(description, "small", True)
        text.setMinimumHeight(28)
        text.setAlignment(Qt.AlignLeft | Qt.AlignTop)
        layout.addWidget(text)
        layout.addStretch()
        for child in self.findChildren(QLabel):
            child.setAttribute(Qt.WA_TransparentForMouseEvents)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Paperwork — PDF Workspace")
        self.setWindowIcon(icon("logo", GREEN, 32))
        self.resize(1240, 880)
        self.setMinimumSize(900, 650)
        self.category = "All tools"
        self.workspace = None

        base = QWidget(objectName="main")
        root = QHBoxLayout(base)
        self.setCentralWidget(base)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        root.addWidget(self.sidebar())

        self.right = QVBoxLayout()
        self.right.setContentsMargins(0, 0, 0, 0)
        self.right.setSpacing(0)
        root.addLayout(self.right, 1)
        self.right.addWidget(self.header())
        self.stack = QStackedWidget()
        self.right.addWidget(self.stack, 1)
        self.home = self.make_home()
        self.stack.addWidget(self.home)
        self.update_catalogue()

        QShortcut(QKeySequence("Ctrl+O"), self, self.open_pdf)
        QShortcut(QKeySequence("Ctrl+K"), self, self.focus_search)
        QShortcut(QKeySequence(Qt.Key_Escape), self, self.escape)

    def sidebar(self):
        rail = QFrame(objectName="sidebar")
        rail.setFixedWidth(203)
        layout = QVBoxLayout(rail)
        layout.setContentsMargins(15, 23, 15, 18)
        layout.setSpacing(6)
        brand = QHBoxLayout()
        brand.setSpacing(9)
        badge = QFrame(objectName="brand")
        badge.setFixedSize(37, 37)
        badge_layout = QHBoxLayout(badge)
        badge_layout.setContentsMargins(0, 0, 0, 0)
        graphic = QLabel()
        graphic.setAlignment(Qt.AlignCenter)
        graphic.setPixmap(icon("logo", "#173d25", 24).pixmap(24, 24))
        badge_layout.addWidget(graphic)
        brand.addWidget(badge)
        text = QVBoxLayout()
        text.setSpacing(0)
        text.addWidget(label("Paperwork", "brandName"))
        text.addWidget(caption("PDF WORKSPACE", "brandMeta"))
        brand.addLayout(text)
        brand.addStretch()
        layout.addLayout(brand)
        layout.addSpacing(24)
        marker = caption("LIBRARY", "sidebarCaption")
        marker.setContentsMargins(9, 0, 0, 5)
        layout.addWidget(marker)

        self.navigation = {}
        self.nav_group = QButtonGroup(self)
        for category, (glyph, _, _) in CATEGORY.items():
            control = button(category, partial(self.set_category, category), style="nav")
            control.setFixedHeight(41)
            control.setIcon(icon(glyph, "#c5dbcf", 18))
            control.setCheckable(True)
            self.nav_group.addButton(control)
            self.navigation[category] = control
            layout.addWidget(control)
        self.navigation["All tools"].setChecked(True)
        layout.addStretch()

        panel = QFrame(objectName="localPanel")
        panel_layout = QHBoxLayout(panel)
        panel_layout.setContentsMargins(9, 11, 8, 11)
        panel_layout.setSpacing(7)
        shield = QLabel()
        shield.setPixmap(icon("shield", "#dff978", 19).pixmap(19, 19))
        panel_layout.addWidget(shield, 0, Qt.AlignTop)
        text = QVBoxLayout()
        text.setSpacing(2)
        text.addWidget(caption("Offline workspace", "localTitle"))
        text.addWidget(caption("Files stay on this device", "localText"))
        panel_layout.addLayout(text)
        panel_layout.addStretch()
        layout.addWidget(panel)
        return rail

    def header(self):
        bar = QFrame(objectName="header")
        bar.setFixedHeight(69)
        row = QHBoxLayout(bar)
        row.setContentsMargins(29, 0, 29, 0)
        row.setSpacing(11)
        self.location = label("Tool library", "subheading")
        row.addWidget(self.location)
        row.addStretch()
        self.search = QLineEdit(objectName="search")
        self.search.setPlaceholderText("Search tools…")
        self.search.setAccessibleName("Search tools")
        self.search.setClearButtonEnabled(True)
        self.search.setFixedWidth(246)
        self.search.addAction(icon("search", "#56756a", 16), QLineEdit.LeadingPosition)
        self.search.textChanged.connect(self.on_search)
        self.search.returnPressed.connect(self.open_matching)
        row.addWidget(self.search)
        open_button = button("Open PDF", self.open_pdf, style="primary", glyph="open")
        open_button.setFixedHeight(38)
        open_button.setToolTip("Open PDF (Ctrl+O)")
        row.addWidget(open_button)
        return bar

    def make_home(self):
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        body = QWidget(objectName="main")
        layout = QVBoxLayout(body)
        layout.setContentsMargins(29, 23, 29, 24)
        layout.setSpacing(15)
        self.hero = Hero(self.open_pdf)
        layout.addWidget(self.hero)
        section = QHBoxLayout()
        section.setSpacing(12)
        self.section_title = label("All tools", "section")
        section.addWidget(self.section_title)
        self.tool_count = caption("")
        section.addWidget(self.tool_count, 0, Qt.AlignBottom)
        section.addStretch()
        layout.addLayout(section)

        filters = QHBoxLayout()
        filters.setSpacing(6)
        self.filters = {}
        self.filter_group = QButtonGroup(self)
        for category in CATEGORY:
            title = "All" if category == "All tools" else category
            control = button(title, partial(self.set_category, category), style="filter")
            control.setCheckable(True)
            self.filter_group.addButton(control)
            self.filters[category] = control
            filters.addWidget(control)
        self.filters["All tools"].setChecked(True)
        filters.addStretch()
        layout.addLayout(filters)

        grid_host = QWidget()
        self.grid = QGridLayout(grid_host)
        self.grid.setContentsMargins(0, 0, 0, 0)
        self.grid.setSpacing(11)
        self.cards = {key: ToolCard(key, self.show_tool) for key in TOOLS}
        for number in range(3):
            self.grid.setColumnStretch(number, 1)
        layout.addWidget(grid_host)
        self.empty = label("No matching tools. Try a different search.", "muted", True)
        layout.addWidget(self.empty)
        layout.addStretch()
        scroll.setWidget(body)
        return scroll

    def update_catalogue(self):
        query = self.search.text().strip().casefold()
        matching = []
        for key, detail in TOOLS.items():
            card = self.cards[key]
            self.grid.removeWidget(card)
            matches = ((self.category == "All tools" or self.category == detail[2])
                       and all(word in " ".join(detail).casefold() for word in query.split()))
            card.setVisible(matches)
            if matches:
                row, column = divmod(len(matching), 3)
                self.grid.addWidget(card, row, column)
                matching.append(key)
        self.matching = matching
        self.tool_count.setText(f"{len(matching)} {'tool' if len(matching) == 1 else 'tools'}")
        self.section_title.setText("Search results" if query else self.category)
        self.hero.setVisible(not query and self.category == "All tools")
        self.empty.setVisible(not matching)

    def return_home(self):
        if self.workspace and not self.workspace.can_leave():
            return False
        if self.workspace:
            self.stack.removeWidget(self.workspace)
            self.workspace.deleteLater()
            self.workspace = None
        self.stack.setCurrentWidget(self.home)
        self.location.setText("Tool library")
        self.search.setEnabled(True)
        return True

    def set_category(self, category):
        if not self.return_home():
            self.navigation[self.category].setChecked(True)
            self.filters[self.category].setChecked(True)
            return
        self.category = category
        self.navigation[category].setChecked(True)
        self.filters[category].setChecked(True)
        self.search.clear()
        self.home.verticalScrollBar().setValue(0)
        self.update_catalogue()

    def on_search(self, text):
        if not self.workspace:
            self.update_catalogue()
            self.home.verticalScrollBar().setValue(0)

    def open_matching(self):
        if self.matching:
            self.show_tool(self.matching[0])

    def focus_search(self):
        if self.return_home():
            self.search.setFocus()
            self.search.selectAll()

    def escape(self):
        if self.workspace:
            self.return_home()
        else:
            self.search.clear()

    def show_tool(self, key, paths=None):
        if not self.return_home():
            return
        self.workspace = Workspace(key, TOOLS[key], self.return_home)
        self.stack.addWidget(self.workspace)
        self.stack.setCurrentWidget(self.workspace)
        self.location.setText(TOOLS[key][0])
        self.search.setEnabled(False)
        if paths:
            self.workspace.add_paths(paths)

    def open_pdf(self):
        paths = Workspace.pick_files(self, "edit")
        if paths:
            self.show_tool("edit", paths)

    def closeEvent(self, event):
        if self.workspace and not self.workspace.can_leave():
            event.ignore()
        else:
            event.accept()


def main(paths=None):
    QApplication.setAttribute(Qt.AA_EnableHighDpiScaling)
    QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps)
    app = QApplication(sys.argv[:1])
    app.setApplicationName("Paperwork")
    app.setDesktopFileName("paperwork-pdf.desktop")
    app.setStyle("Fusion")
    app.setStyleSheet(STYLE)
    window = MainWindow()
    window.show()
    if paths:
        QTimer.singleShot(0, partial(window.show_tool, "edit", paths[:1]))
    return app.exec()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
