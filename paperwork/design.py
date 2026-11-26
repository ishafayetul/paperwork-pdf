"""Paperwork's Qt styling and artwork."""

from PyQt5.QtCore import QPointF, QRectF, Qt
from PyQt5.QtGui import QColor, QIcon, QLinearGradient, QPainter, QPainterPath, QPen, QPixmap
from PyQt5.QtWidgets import QLabel, QPushButton


INK = "#172e29"
GREEN = "#19483d"
PAPER = "#f6f8f5"
PALE = "#62766e"

STYLE = """
QWidget {font-family: Lato, "DejaVu Sans"; font-size: 14px; color: #172e29;}
QMainWindow, QDialog, QWidget#main {background: #f6f8f5;}
QFrame#sidebar {background: #102923;}
QFrame#header {background: #ffffff; border-bottom: 1px solid #e1e8e2;}
QFrame#brand {background: #dff978; border-radius: 11px;}
QLabel#brandName {color: #f5faf6; font-size: 22px; font-weight: 800;}
QLabel#brandMeta {color: #a4bcb0; font-size: 10px; font-weight: 700; letter-spacing: 1px;}
QLabel#sidebarCaption {color: #a4bcb0; font-size: 11px; font-weight: 800;}
QFrame#sidebarLine {background: #2b453d;}
QFrame#localPanel {background: #1b362e; border: 1px solid #304c42; border-radius: 12px;}
QLabel#localTitle {color: #e3f4e9; font-weight: 800; font-size: 12px;}
QLabel#localText {color: #bed0c5; font-size: 11px;}
QPushButton {background: white; border: 1px solid #d9e3da; border-radius: 8px;
             padding: 7px 11px; font-weight: 700;}
QPushButton:hover {background: #eef5ef; border-color: #b4cbbd;}
QPushButton:focus, QLineEdit:focus, QComboBox:focus, QSpinBox:focus, QListWidget:focus,
QPlainTextEdit:focus {border: 2px solid #43856e;}
QPushButton:disabled {color: #72857c; background: #e9efea; border-color: #e1e9e2;}
QPushButton#nav {background: transparent; color: #c5d6cd; text-align: left; border: 1px solid transparent;
                 border-radius: 8px; padding: 8px 12px;}
QPushButton#nav:hover, QPushButton#nav:focus {background: #25463b; color: #fff; border-color: #496659;}
QPushButton#nav:checked {background: #2b4d40; color: #edffcd; border-color: #3c5d4c;}
QPushButton#primary {background: #19483d; color: white; border-color: #19483d; padding: 8px 15px;}
QPushButton#primary:hover, QPushButton#primary:focus {background: #286351; border-color: #5f9b86;}
QPushButton#primary:disabled {background: #dfe9e1; color: #647b6e; border-color: #dfe9e1;}
QPushButton#heroButton {background: #ddf77c; color: #132d1a; border-color: #ddf77c; padding: 8px 15px;}
QPushButton#heroButton:hover, QPushButton#heroButton:focus {background: #ecffa7; border-color: #fff;}
QPushButton#filter {border-radius: 15px; padding: 5px 12px; font-size: 12px; color: #445d52;}
QPushButton#filter:checked {background: #e1eae2; color: #204536; border-color: #c8dbcd;}
QPushButton#toolCard {border-radius: 13px; background: #ffffff; border: 1px solid #dfe7df; padding: 0;}
QPushButton#toolCard:hover {background: #fbfef9; border-color: #88b49f;}
QPushButton#toolCard:focus {border: 2px solid #43856e;}
QFrame#surface {background: white; border: 1px solid #dfe7df; border-radius: 13px;}
QFrame#dropZone {background: #edf4ee; border: 2px dashed #a7c1af; border-radius: 13px;}
QFrame#previewFrame {background: #e6ede7; border: 1px solid #d6e1d8; border-radius: 13px;}
QLabel#muted {color: #526c60;}
QLabel#small {color: #526c60; font-size: 12px;}
QLabel#heading {font-size: 27px; font-weight: 800;}
QLabel#section {font-size: 22px; font-weight: 800;}
QLabel#subheading {font-size: 16px; font-weight: 800;}
QLabel#heroTitle {color: #fbfff8; font-size: 37px; font-weight: 900;}
QLabel#heroCopy {color: #d4e6dd; font-size: 15px;}
QLineEdit, QComboBox, QSpinBox, QPlainTextEdit {color: #182f28; background: #fbfcfa;
    border: 1px solid #d7e2d9; border-radius: 8px; padding: 6px 8px; selection-background-color: #c5e1cd;
    selection-color: #172e29;}
QLineEdit#search {background: #f4f7f3; padding-left: 6px; min-height: 24px;}
QListWidget {border: 1px solid #d7e2d9; border-radius: 8px; padding: 4px; background: #fcfdfb;
             outline: 0;}
QListWidget::item {padding: 6px 8px; min-height: 22px;}
QListWidget::item:selected {background: #dceee1; color: #183d2f; border-radius: 5px;}
QScrollArea {background: transparent; border: 0;}
QScrollBar:vertical {width: 9px; background: transparent; margin: 2px 1px;}
QScrollBar::handle:vertical {background: #afc3b7; border-radius: 3px; min-height: 26px;}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {height: 0;}
QProgressBar {border: 0; background: #e5eee6; border-radius: 3px; max-height: 6px;}
QProgressBar::chunk {background: #428168; border-radius: 3px;}
"""


def label(text, style="", wrap=False):
    widget = QLabel(text)
    widget.setTextFormat(Qt.PlainText)
    widget.setObjectName(style)
    widget.setWordWrap(wrap)
    return widget


def draw_icon(p, name, color):
    p.setRenderHint(QPainter.Antialiasing)
    p.setPen(QPen(QColor(color), 1.65, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin))
    p.setBrush(Qt.NoBrush)

    def line(*points):
        path = QPainterPath(QPointF(*points[0]))
        for point in points[1:]:
            path.lineTo(*point)
        p.drawPath(path)

    def rect(x, y, w, h, radius=1.6):
        p.drawRoundedRect(QRectF(x, y, w, h), radius, radius)

    def ellipse(x, y, w, h):
        p.drawEllipse(QRectF(x, y, w, h))

    def document():
        line((6, 3), (14, 3), (19, 8), (19, 21), (5, 21), (5, 4), (6, 3))
        line((14, 3), (14, 8), (19, 8))

    if name in {"logo", "pdf", "markdown", "numbers"}:
        document()
        if name == "markdown":
            line((8, 16), (8, 11), (10.5, 14), (13, 11), (13, 16))
            line((16, 11), (16, 16), (14.5, 14.5))
        elif name == "numbers":
            line((9, 17), (15, 17))
            line((9, 13), (15, 13))
            line((10.5, 18.5), (11.8, 11.5))
            line((13.1, 18.5), (14.4, 11.5))
        else:
            line((8.5, 12), (15.5, 12))
            line((8.5, 16), (14, 16))
    elif name in {"grid", "organize"}:
        for x in (3.5, 13):
            for y in (3.5, 13):
                rect(x, y, 7.5, 7.5)
    elif name == "merge":
        rect(3, 3, 7, 9)
        rect(14, 3, 7, 9)
        line((6.5, 15), (6.5, 17.5), (17.5, 17.5), (17.5, 15))
        line((12, 17.5), (12, 22))
    elif name == "split":
        ellipse(3, 4, 6, 6)
        ellipse(3, 14, 6, 6)
        line((8, 8.9), (20, 20.5))
        line((8, 15.1), (20, 3.5))
    elif name == "compress":
        line((4, 9), (9, 9), (9, 4))
        line((20, 9), (15, 9), (15, 4))
        line((4, 15), (9, 15), (9, 20))
        line((20, 15), (15, 15), (15, 20))
        for a, b in [((4, 4), (9, 9)), ((20, 4), (15, 9)),
                     ((4, 20), (9, 15)), ((20, 20), (15, 15))]:
            line(a, b)
    elif name == "edit":
        line((17, 13), (17, 21), (3.5, 21), (3.5, 6), (11, 6))
        line((9, 15.5), (10, 11.5), (19, 2.5), (22, 5.5), (13, 14.5), (9, 15.5))
        line((16.5, 5), (19.5, 8))
    elif name in {"images-pdf", "pdf-images"}:
        rect(3, 4, 18, 16)
        ellipse(6.5, 7, 3.7, 3.7)
        line((4, 17), (9, 13), (12, 16), (16, 11), (21, 16))
    elif name == "crop":
        line((7, 3), (7, 17), (21, 17))
        line((3, 7), (17, 7), (17, 21))
    elif name == "watermark":
        path = QPainterPath(QPointF(12, 2.8))
        path.cubicTo(15, 7, 19, 11, 19, 14.8)
        path.cubicTo(19, 23, 5, 23, 5, 14.8)
        path.cubicTo(5, 11, 9, 7, 12, 2.8)
        p.drawPath(path)
        p.drawArc(QRectF(8, 11, 8, 7), 185 * 16, 60 * 16)
    elif name in {"protect", "unlock", "shield"}:
        if name == "shield":
            line((12, 3), (4.5, 6), (5, 13), (8, 18), (12, 21),
                 (16, 18), (19, 13), (19.5, 6), (12, 3))
            line((8.5, 12.4), (11, 15), (15.8, 9.6))
        else:
            rect(5, 10, 14, 11)
            p.drawArc(QRectF(8, 3, 8, 11), 0, 180 * 16)
            line((8, 8), (8, 10))
            if name == "protect":
                line((16, 8), (16, 10))
            line((12, 14), (12, 17))
    elif name == "open":
        line((3, 19), (3, 6), (9, 6), (11, 8), (20, 8), (20, 10))
        line((3, 19), (7, 11), (22, 11), (18, 19), (3, 19))
    elif name == "search":
        ellipse(4, 4, 12, 12)
        line((14.5, 14.5), (20, 20))
    elif name in {"left", "right"}:
        if name == "left":
            line((20, 12), (4, 12), (10, 6))
            line((4, 12), (10, 18))
        else:
            line((4, 12), (20, 12), (14, 6))
            line((20, 12), (14, 18))
    elif name == "check":
        line((4, 12), (10, 18), (20, 6))


def icon(name, color=GREEN, size=20):
    bitmap = QPixmap(size * 2, size * 2)
    bitmap.fill(Qt.transparent)
    painter = QPainter(bitmap)
    painter.scale(size * 2 / 24, size * 2 / 24)
    draw_icon(painter, name, color)
    painter.end()
    bitmap.setDevicePixelRatio(2)
    return QIcon(bitmap)


def button(text, callback, *, style="", glyph=None):
    control = QPushButton(text)
    control.setObjectName(style)
    control.setCursor(Qt.PointingHandCursor)
    if glyph:
        control.setIcon(icon(glyph, "#ffffff" if style == "primary" else GREEN, 17))
    control.clicked.connect(callback)
    return control


def draw_hero(painter, outer):
    painter.setRenderHint(QPainter.Antialiasing)
    path = QPainterPath()
    path.addRoundedRect(outer, 16, 16)
    painter.setClipPath(path)
    fill = QLinearGradient(0, 0, outer.width(), outer.height())
    fill.setColorAt(0, QColor("#143e34"))
    fill.setColorAt(1, QColor("#235e4e"))
    painter.fillRect(outer, fill)

    painter.translate(outer.width() - 322, outer.height() / 2)
    painter.setBrush(Qt.NoBrush)
    for offset in range(0, 96, 13):
        painter.setPen(QPen(QColor(172, 224, 182, 33), 1))
        painter.drawEllipse(QRectF(72 - offset, -74 - offset, 172 + offset * 2, 148 + offset * 2))
    for x in (9, 38, 265, 288):
        for y in (-61, -29, 6, 46):
            painter.setPen(Qt.NoPen)
            painter.setBrush(QColor(183, 222, 181, 127))
            painter.drawEllipse(QPointF(x, y), 1.25, 1.25)

    def card(x, y, angle, fill_color, border):
        painter.save()
        painter.translate(x, y)
        painter.rotate(angle)
        painter.setPen(QPen(QColor(border), 1.4))
        painter.setBrush(QColor(fill_color))
        painter.drawRoundedRect(QRectF(-50, -69, 100, 138), 11, 11)

    card(119, 2, -21, "#397364", "#659883")
    painter.setPen(QPen(QColor("#7ba796"), 2, cap=Qt.RoundCap))
    for top, length in [(-39, 43), (-27, 53), (-15, 30)]:
        painter.drawLine(QPointF(-32, top), QPointF(-32 + length, top))
    painter.restore()

    card(198, 12, 19, "#72a58d", "#a2c4a8")
    painter.setPen(QPen(QColor("#477864"), 2, cap=Qt.RoundCap))
    for top, length in [(-38, 46), (-26, 56), (-14, 35)]:
        painter.drawLine(QPointF(-30, top), QPointF(-30 + length, top))
    painter.restore()

    card(158, -4, -4, "#f0f8dd", "#edffcb")
    painter.setPen(Qt.NoPen)
    painter.setBrush(QColor("#dbef8b"))
    painter.drawRoundedRect(QRectF(-32, -48, 35, 34), 7, 7)
    painter.save()
    painter.translate(-25, -41)
    painter.scale(.86, .86)
    draw_icon(painter, "pdf", "#294e38")
    painter.restore()
    painter.setPen(QPen(QColor("#76917e"), 2, cap=Qt.RoundCap))
    for top, length in [(5, 56), (17, 65), (29, 49), (41, 34)]:
        painter.drawLine(QPointF(-32, top), QPointF(-32 + length, top))
    painter.restore()

    painter.setPen(QPen(QColor("#def67f"), 2, cap=Qt.RoundCap))
    for x, y, radius in [(257, -57, 7), (68, 48, 5), (240, 70, 4)]:
        painter.drawLine(QPointF(x - radius, y), QPointF(x + radius, y))
        painter.drawLine(QPointF(x, y - radius), QPointF(x, y + radius))
