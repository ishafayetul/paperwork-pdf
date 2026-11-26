"""Build local fixtures and verify each tool's output."""
import hashlib
import io
import os
import stat
import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile

from PIL import Image

from paperwork.engine import InputError, open_pdf, page_groups, pdf, run


def make_document(path, titles, *, scan=None):
    with pdf.open() as doc:
        for n, title in enumerate(titles):
            page = doc.new_page(width=360, height=480)
            page.insert_text((34, 59), title, fontsize=24)
            page.insert_text((34, 92), "A selectable sentence for a dependable local PDF test.")
            if n == 1:
                page.set_cropbox(pdf.Rect(10, 15, 350, 460))
                page.set_rotation(90)
            if scan:
                page.insert_image(pdf.Rect(34, 110, 322, 428), filename=scan)
        doc.save(path)


def check(directory):
    a = directory / "first document.pdf"
    b = directory / "second.pdf"
    pic = directory / "image.png"
    Image.effect_noise((1800, 1450), 70).convert("RGB").save(pic)
    make_document(a, ("First", "Second", "Third"))
    make_document(b, ("Fourth",), scan=pic)
    original = hashlib.sha256(a.read_bytes()).digest()

    def export(tool, inputs=None, file=None, **options):
        target = directory / (file or f"{tool}.pdf")
        sources = inputs or [(a, "")]
        request = {"tool": tool, "sources": [(str(path), pw) for path, pw in sources],
                   "destination": str(target), "options": options}
        assert run(request)
        return target

    assert page_groups("2-3, 1", 3) == [[1, 2], [0]]
    try:
        page_groups("0, 4", 3)
    except InputError:
        pass
    else:
        raise AssertionError("Accepted invalid page ranges")

    merged = export("merge", [(b, ""), (a, "")])
    assert stat.S_IMODE(merged.stat().st_mode) == 0o600
    with open_pdf(merged) as doc:
        assert len(doc) == 4 and "Fourth" in doc[0].get_text()

    split = export("split", file="split.zip", pages="1-2, 3", mode="Separate PDFs (ZIP)")
    with ZipFile(split) as archive:
        assert len(archive.namelist()) == 2
        with pdf.open("pdf", archive.read("part-0001.pdf")) as doc:
            assert len(doc) == 2
    extracted = export("split", pages="3, 1", mode="One PDF (extract ranges)")
    with open_pdf(extracted) as doc:
        assert len(doc) == 2 and "Third" in doc[0].get_text()

    compressed = export("compress", [(b, "")], level="Smallest")
    assert compressed.stat().st_size < b.stat().st_size / 2
    with open_pdf(compressed) as doc:
        assert "Fourth" in doc[0].get_text()
        assert doc[0].get_images()
    assert export("compress", level="Lossless").stat().st_size <= a.stat().st_size

    output = export("organize", layout=[(1, 0), (1, 90), (-1, 0), (0, 0)])
    with open_pdf(output) as doc:
        assert len(doc) == 4
        assert [p.rotation for p in doc] == [90, 180, 0, 0]
        assert "First" in doc[3].get_text() and not doc[2].get_text().strip()

    before = []
    with open_pdf(a) as doc:
        before = [page.rect for page in doc]
    crop = export("crop", box=(.10, .20, .85, .80), page=1, all=True)
    with open_pdf(crop) as doc:
        for rect, page in zip(before, doc):
            assert abs(page.rect.width - rect.width * .75) < 1
            assert abs(page.rect.height - rect.height * .60) < 1
            page.get_pixmap()
        assert doc[1].rotation == 90

    edits = [
        {"kind": "Text", "page": 0, "text": "Reviewed", "box": (.10, .30, .95, .43), "size": 16},
        {"kind": "Text", "page": 1, "text": "Édition", "box": (.08, .42, .90, .56), "size": 16},
        {"kind": "Rectangle", "page": 0, "box": (.16, .47, .40, .57)},
        {"kind": "Highlight", "page": 0, "box": (.10, .17, .60, .21)},
        {"kind": "Image", "page": 1, "image": str(pic), "box": (.08, .62, .35, .89)},
    ]
    for edit in edits:
        edit["color"] = (.25, .35, .40)
    edited = export("edit", edits=edits)
    with open_pdf(edited) as doc:
        assert "Reviewed" in doc[0].get_text()
        assert "Édition" in doc[1].get_text()
        assert len(doc[0].get_drawings()) == 2
        assert len(doc[1].get_images()) == 1
        doc[1].get_pixmap()
    assert hashlib.sha256(a.read_bytes()).digest() == original

    for paper in ("A4 portrait", "Fit to image"):
        image_pdf = export("images-pdf", [(pic, ""), (pic, "")], paper=paper, margin=24)
        with open_pdf(image_pdf) as doc:
            assert len(doc) == 2 and doc[0].get_images()
            assert round(doc[0].rect.width) == (595 if paper == "A4 portrait" else 1350)

    for fmt in ("png", "jpeg"):
        images = export("pdf-images", file="images.zip", pages="2", format=fmt, dpi=144)
        with ZipFile(images) as archive:
            assert len(archive.namelist()) == 1
            with Image.open(io.BytesIO(archive.read(archive.namelist()[0]))) as bitmap:
                assert bitmap.size == (890, 680) and bitmap.format == fmt.upper()

    notes = export("markdown", file="notes.md").read_text()
    assert "### First" in notes and "## Page 3" in notes and "selectable sentence" in notes
    watermark = export("watermark", text="DRAFT", size=28, opacity=35)
    with open_pdf(watermark) as doc:
        assert all("DRAFT" in page.get_text() for page in doc)
    numbers = export("numbers", position="Right", start=12)
    with open_pdf(numbers) as doc:
        assert all(str(n) in page.get_text().split() for n, page in enumerate(doc, 12))
    sample_password = "fixture-password"
    secured = export("protect", secret=sample_password)
    with pdf.open(secured) as doc:
        assert doc.needs_pass and not doc.authenticate("wrong")
        assert doc.authenticate(sample_password)
    secure_crop = export("crop", [(secured, sample_password)], box=(.1, .1, .9, .9),
                         all=False, page=0)
    with pdf.open(secure_crop) as doc:
        assert doc.needs_pass and doc.authenticate(sample_password)
    clear = export("unlock", [(secured, sample_password)])
    with open_pdf(clear) as doc:
        assert len(doc) == 3 and not doc.needs_pass

    hardlink = directory / "same.pdf"
    os.link(a, hardlink)
    for file in (a.name, hardlink.name):
        try:
            export("compress", file=file, level="Lossless")
        except InputError:
            pass
        else:
            raise AssertionError("Allowed overwrite of source")
    prior = edited.read_bytes()
    try:
        export("edit", edits=[{"kind": "Text", "page": 0, "text": "Oversized", "size": 96,
                               "box": (.1, .1, .12, .12), "color": (0, 0, 0)}])
    except InputError:
        assert edited.read_bytes() == prior
        assert not list(directory.glob(".paperwork-*"))
    else:
        raise AssertionError("Accepted text that did not fit")


if __name__ == "__main__":
    if len(sys.argv) == 2:
        check(Path(sys.argv[1]))
    else:
        with TemporaryDirectory() as temp:
            check(Path(temp))
    print("Verified all 13 PDF tools and source/failed-export protections.")
