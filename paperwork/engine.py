"""Local PDF operations. The GUI runs exports in a separate process."""

import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

runtime = Path(__file__).resolve().parent.parent / ".runtime"
if runtime.is_dir():
    sys.path.insert(0, str(runtime))

import pymupdf as pdf


IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".gif"}


class InputError(ValueError):
    pass


class PasswordRequired(InputError):
    pass


def open_pdf(path, password=""):
    doc = pdf.open(path)
    if not doc.is_pdf:
        doc.close()
        raise InputError("Choose a valid PDF file.")
    if doc.needs_pass and not doc.authenticate(password):
        doc.close()
        raise PasswordRequired("Enter the correct password to open this PDF.")
    if not doc.page_count:
        doc.close()
        raise InputError("This PDF has no pages.")
    return doc


def page_groups(value, count):
    """Parse 1-based inclusive ranges into separate groups of 0-based indices."""
    if not value.strip():
        return [list(range(count))]
    groups = []
    for token in value.split(","):
        match = re.fullmatch(r"\s*([0-9]{1,9})(?:\s*-\s*([0-9]{1,9}))?\s*", token)
        if not match:
            raise InputError("Use page numbers and ranges such as 1-3, 6, 9-12.")
        first = int(match[1])
        last = int(match[2] or match[1])
        if not 1 <= first <= last <= count:
            raise InputError(f"Each range must be in ascending order within pages 1–{count}.")
        groups.append(list(range(first - 1, last)))
    return groups


def pixmap(page, scale, *, rotation=0, alpha=False):
    bounds = page.rect
    if (bounds.width <= 0 or bounds.height <= 0
            or max(bounds.width, bounds.height) * scale > 18000
            or bounds.width * bounds.height * scale ** 2 > 32_000_000):
        raise InputError("The rendered page is too large. Choose a lower resolution.")
    return page.get_pixmap(matrix=pdf.Matrix(scale, scale).prerotate(rotation),
                           colorspace=pdf.csRGB, alpha=alpha)


def view_rect(page, box):
    x0, y0, x1, y1 = box
    if not 0 <= x0 < x1 <= 1 or not 0 <= y0 < y1 <= 1:
        raise InputError("Draw a rectangle inside the page.")
    width, height = page.rect.width, page.rect.height
    return pdf.Rect(x0 * width, y0 * height, x1 * width, y1 * height)


def crop_page(page, box):
    area = view_rect(page, box) * page.derotation_matrix
    origin = page.cropbox.top_left
    area += (origin.x, origin.y, origin.x, origin.y)
    area &= page.cropbox
    if min(area.width, area.height) < 2:
        raise InputError("The crop area must be at least 2 points wide and high.")
    page.set_cropbox(area)


def draw_text(page, area, value, size, color, opacity=1, align=0):
    # Use HTML shaping for mixed-script text when the built-in Helvetica cannot encode it.
    if not value.isascii():
        from html import escape

        rgb = tuple(round(channel * 255) for channel in color)
        css = (f"body {{ margin: 0; font-size: {size}pt; color: rgb{rgb}; "
               f"text-align: {('left', 'center', 'right')[align]}; }}")
        free, _ = page.insert_htmlbox(area, escape(value).replace("\n", "<br>"),
                                      css=css, scale_low=1, rotate=page.rotation, opacity=opacity)
    else:
        free = page.insert_textbox(area, value, fontsize=size, color=color, align=align,
                                  rotate=page.rotation, fill_opacity=opacity)
    if free < 0:
        raise InputError("The text does not fit. Draw a larger box or use a smaller font.")


def apply_edit(page, edit):
    area = view_rect(page, edit["box"]) * page.derotation_matrix
    color = tuple(edit["color"])
    mode = edit["kind"]
    if mode == "Text":
        draw_text(page, area, edit["text"], edit["size"], color)
    elif mode == "Rectangle":
        page.draw_rect(area, color=color, width=1.6)
    elif mode == "Highlight":
        page.draw_rect(area, color=None, fill=color, fill_opacity=.30)
    elif mode == "Image":
        with open_image(edit["image"]) as image:
            # Rendering the image honors EXIF rotation and limits marked-up image size.
            image_page = image[0]
            ratio = min(1.0, 2200 / max(image_page.rect.width, image_page.rect.height))
            stamp = pixmap(image_page, ratio, alpha=True)
            page.insert_image(area, pixmap=stamp, rotate=page.rotation)
    else:
        raise InputError("Unknown edit type.")


def open_image(path):
    if Path(path).suffix.lower() not in IMAGE_SUFFIXES:
        raise InputError("Choose a PNG, JPG, BMP, TIFF or GIF image.")
    doc = pdf.open(path)
    if (doc.metadata or {}).get("format") not in {"Image", "BMP", "TIFF"} or not doc.page_count:
        doc.close()
        raise InputError("Choose a valid image file.")
    return doc


def atomic_file(destination, action):
    target = Path(destination)
    # MuPDF can recreate its output file. Keep it inside a private directory until publish.
    with tempfile.TemporaryDirectory(prefix=".paperwork-", dir=target.parent) as work:
        temp = Path(work) / ("export" + target.suffix)
        result = action(temp)
        temp.chmod(0o600)
        os.replace(temp, target)
        return result


def save_pdf(doc, target, encryption=pdf.PDF_ENCRYPT_KEEP, **kwargs):
    doc.save(target, garbage=3, deflate=True, deflate_images=True, deflate_fonts=True,
             use_objstms=1, encryption=encryption, **kwargs)


def selected_pages(doc, value):
    return [page for group in page_groups(value, doc.page_count) for page in group]


def validate_password(secret):
    if not (1 <= len(secret) <= 40 and len(secret.encode("utf-8")) <= 127) or "\0" in secret:
        raise InputError("Use 1–40 characters and at most 127 UTF-8 bytes for the password.")


def zip_pages(doc, target, options, images=False):
    value = options["pages"]
    groups = page_groups(value, doc.page_count)
    with ZipFile(target, "w", ZIP_STORED) as bundle:
        if images:
            fmt = options["format"]
            dpi = int(options["dpi"])
            if fmt not in {"png", "jpeg"} or not 72 <= dpi <= 300:
                raise InputError("Choose PNG or JPEG at 72–300 DPI.")
            ext = "jpg" if fmt == "jpeg" else fmt
            pages = dict.fromkeys(page for group in groups for page in group)
            for index in pages:
                rendered = pixmap(doc[index], dpi / 72)
                bundle.writestr(f"page-{index + 1:04d}.{ext}", rendered.tobytes(fmt))
            return f"Exported {len(pages)} page images."
        if not value.strip():
            groups = [[number] for number in range(doc.page_count)]
        for number, group in enumerate(groups, 1):
            with pdf.open() as part:
                part.insert_pdf(doc, from_page=group[0], to_page=group[-1])
                bundle.writestr(f"part-{number:04d}.pdf", part.tobytes(garbage=3, deflate=True))
    return f"Created {len(groups)} PDFs."


def combine(tool, sources, target, options):
    with pdf.open() as out:
        for path, password in sources:
            if tool == "merge":
                with open_pdf(path, password) as source:
                    out.insert_pdf(source)
                continue
            with open_image(path) as image:
                converted = image.convert_to_pdf()
                with pdf.open("pdf", converted) as source:
                    for im in source:
                        if options["paper"] == "Fit to image":
                            # PDF page sizes above 14,400 pt are not portable across viewers.
                            if max(im.rect.width, im.rect.height) <= 14400:
                                out.insert_pdf(source, from_page=im.number, to_page=im.number)
                                continue
                            size = im.rect * (14400 / max(im.rect.width, im.rect.height))
                            page = out.new_page(width=size.width, height=size.height)
                            page.show_pdf_page(page.rect, source, im.number)
                        else:
                            page = out.new_page(width=595.28, height=841.89)
                            margin = options["margin"]
                            page.show_pdf_page(page.rect + (margin, margin, -margin, -margin),
                                               source, im.number)
        save_pdf(out, target)
        return f"Saved a {out.page_count}-page PDF."


def markdown(doc, target):
    # shortcut: text-based layout heuristics, add an OCR/layout engine for scan-heavy workflows.
    def escape(text):
        return re.sub(r"([\\`*_{}\[\]<>|])", r"\\\1", text)

    any_text = False
    with target.open("w", encoding="utf-8") as output:
        for page in doc:
            blocks = page.get_text("dict", flags=pdf.TEXTFLAGS_DICT & ~pdf.TEXT_PRESERVE_IMAGES,
                                   sort=True)["blocks"]
            lines = [line for block in blocks for line in block.get("lines", [])]
            sizes = {}
            for line in lines:
                for span in line["spans"]:
                    size = round(span["size"], 1)
                    sizes[size] = sizes.get(size, 0) + len(span["text"].strip())
            body_size = max(sizes, key=sizes.get, default=12)
            output.write(f"## Page {page.number + 1}\n\n")
            page_has_text = False
            for block in blocks:
                paragraph = []
                for line in block.get("lines", []):
                    spans = line["spans"]
                    plain = "".join(span["text"] for span in spans).strip()
                    if not plain:
                        continue
                    page_has_text = True
                    size = max(span["size"] for span in spans)
                    if size >= 1.25 * body_size and len(plain) <= 130:
                        paragraph.extend(("", "### " + escape(plain), ""))
                    elif plain.startswith(("• ", "◦ ", "▪ ")):
                        paragraph.append("- " + escape(plain[2:]))
                    else:
                        paragraph.append(escape(plain))
                if paragraph:
                    output.write("\n".join(paragraph).strip("\n") + "\n\n")
            if not page_has_text:
                output.write("*No selectable text on this page.*\n\n")
            any_text |= page_has_text
    if not any_text:
        raise InputError("This PDF has no selectable text. Run OCR before exporting to Markdown.")
    return f"Exported text from {doc.page_count} pages."


def pdf_operation(tool, doc, source, target, opts):
    if tool == "split":
        if opts["mode"] == "Separate PDFs (ZIP)":
            return zip_pages(doc, target, opts)
        if not opts["pages"].strip():
            raise InputError("Enter at least one page or range to extract.")
        doc.select(selected_pages(doc, opts["pages"]))
    elif tool == "pdf-images":
        return zip_pages(doc, target, opts, images=True)
    elif tool == "markdown":
        return markdown(doc, target)
    elif tool == "compress":
        levels = {"Balanced": (190, 150, 75), "Smallest": (140, 100, 57)}
        if opts["level"] in levels:
            threshold, dpi, quality = levels[opts["level"]]
            doc.rewrite_images(dpi_threshold=threshold, dpi_target=dpi, quality=quality)
        save_pdf(doc, target)
        original = Path(source).stat().st_size
        reduced = target.stat().st_size
        if reduced >= original:
            shutil.copyfile(source, target)
            return "This preset found no size reduction. Saved an identical copy."
        return f"Reduced size by {(original - reduced) / original:.1%}."
    elif tool == "organize":
        layout = opts["layout"]
        if not layout:
            raise InputError("Keep at least one page.")
        # select() aliases repeated page objects; use full copies for independently rotated duplicates.
        used, order = set(), []
        for index, angle in layout:
            if index == -1:
                doc.new_page()
                index = doc.page_count - 1
            elif index in used:
                doc.fullcopy_page(index)
                index = doc.page_count - 1
            used.add(index)
            order.append(index)
        doc.select(order)
        for page, (_, angle) in zip(doc, layout):
            page.set_rotation((page.rotation + angle) % 360)
    elif tool == "crop":
        if not opts["box"]:
            raise InputError("Drag over the area you want to keep.")
        pages = doc if opts["all"] else [doc[opts["page"]]]
        for page in pages:
            crop_page(page, opts["box"])
    elif tool == "edit":
        if not opts["edits"]:
            raise InputError("Add at least one edit before exporting.")
        for edit in opts["edits"]:
            apply_edit(doc[edit["page"]], edit)
    elif tool in {"numbers", "watermark"}:
        for index, page in enumerate(doc, 1):
            width, height = page.rect.width, page.rect.height
            if tool == "numbers":
                area = pdf.Rect(12, height - 28, width - 12, height - 7)
                align = {"Left": 0, "Center": 1, "Right": 2}[opts["position"]]
                draw_text(page, area * page.derotation_matrix, str(index + opts["start"] - 1),
                          10, (.17, .21, .24), align=align)
            else:
                area = pdf.Rect(width * .06, height * .39, width * .94, height * .60)
                draw_text(page, area * page.derotation_matrix, opts["text"], opts["size"],
                          (.22, .28, .31), opts["opacity"] / 100, align=1)
    elif tool == "protect":
        secret = opts["secret"]
        validate_password(secret)
        save_pdf(doc, target, encryption=pdf.PDF_ENCRYPT_AES_256, user_pw=secret,
                 owner_pw=secret)
        return "Saved an AES-256 encrypted PDF."
    elif tool == "unlock":
        if doc.xref_get_key(-1, "Encrypt")[0] == "null":
            raise InputError("This PDF does not have password encryption.")
        save_pdf(doc, target, encryption=pdf.PDF_ENCRYPT_NONE)
        return "Saved a PDF without password encryption."
    else:
        raise InputError("Unknown PDF tool.")
    save_pdf(doc, target)
    return f"Saved a {doc.page_count}-page PDF."


def run(job):
    tool, sources, destination, options = (job[key] for key in ("tool", "sources", "destination", "options"))
    if not sources or (tool == "merge" and len(sources) < 2):
        raise InputError("Add the required input files.")
    target = Path(destination)
    inputs = [path for path, _ in sources]
    inputs.extend(edit["image"] for edit in options.get("edits", []) if edit["kind"] == "Image")
    for path in map(Path, inputs):
        if target.resolve() == path.resolve() or (target.exists() and target.samefile(path)):
            raise InputError("Choose an output file different from the source.")

    def export(temp):
        if tool in {"merge", "images-pdf"}:
            return combine(tool, sources, temp, options)
        path, password = sources[0]
        with open_pdf(path, password) as doc:
            return pdf_operation(tool, doc, path, temp, options)

    return atomic_file(target, export)


if __name__ == "__main__":
    try:
        response = {"message": run(json.load(sys.stdin))}
    except InputError as error:
        response = {"error": str(error)}
    except Exception:
        response = {"error": "The PDF engine could not finish this operation. Check the inputs and output location."}
    sys.stdout.write(json.dumps(response))
