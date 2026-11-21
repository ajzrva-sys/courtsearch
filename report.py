"""PDF copy of one cached case, using the case portal's own header image."""

import os
from datetime import datetime, timezone

from fpdf import FPDF

HERE = os.path.dirname(os.path.abspath(__file__))
HEADER = os.path.join(HERE, "static", "court-header.jpg")


def render_case_pdf(case):
    pdf = FPDF(format="letter")
    pdf.set_auto_page_break(auto=True, margin=18)
    pdf.add_page()
    pdf.image(HEADER, x=12, y=10, w=192)
    pdf.set_y(38)
    pdf.set_font("Helvetica", "B", 14)
    pdf.cell(0, 8, _safe(case["case_number"]), new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 11)
    pdf.cell(0, 6, _safe(f"{case['court']}  |  {case['division']}"), new_x="LMARGIN", new_y="NEXT")
    pdf.ln(2)
    _fields(pdf, "From the search", [
        ("Type / charge", case.get("case_type")),
        ("Plaintiff", case.get("plaintiff")),
        ("Defendant", case.get("defendant")),
        ("Judgment", case.get("judgment")),
        ("Hearing", " ".join(part for part in (case.get("hearing_date"), case.get("hearing_time")) if part)),
        ("Name searched", case.get("searched_name")),
    ])
    detail = case.get("detail") or {}
    detail_fields = [(label, value) for label, value in (detail.get("fields") or {}).items() if value]
    if detail_fields:
        _fields(pdf, "Case record", detail_fields)
    for table in detail.get("tables") or []:
        _table(pdf, table)
    pdf.ln(4)
    printed = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M %Z")
    pdf.set_font("Helvetica", "", 8)
    pdf.set_text_color(80, 80, 80)
    pdf.cell(0, 5, _safe(f"Printed {printed}"), new_x="LMARGIN", new_y="NEXT")
    return bytes(pdf.output())


def _fields(pdf, title, rows):
    pdf.ln(3)
    pdf.set_x(pdf.l_margin)
    pdf.set_text_color(30, 58, 95)
    pdf.set_font("Helvetica", "B", 10)
    pdf.cell(0, 6, _safe(title.upper()), new_x="LMARGIN", new_y="NEXT")
    pdf.set_text_color(0, 0, 0)
    label_w, value_w = 46, 140
    for label, value in rows:
        if not value:
            continue
        y = pdf.get_y()
        if y > 250:
            pdf.add_page()
            y = pdf.get_y()
        pdf.set_xy(pdf.l_margin, y)
        pdf.set_font("Helvetica", "B", 9)
        pdf.multi_cell(label_w, 5, _safe(label))
        label_bottom = pdf.get_y()
        pdf.set_xy(pdf.l_margin + 50, y)
        pdf.set_font("Helvetica", "", 9)
        pdf.multi_cell(value_w, 5, _safe(str(value)))
        pdf.set_y(max(label_bottom, pdf.get_y()))


def render_results_pdf(name, cases):
    """One PDF of the results currently on the page, with the court header."""
    pdf = FPDF(format="letter", orientation="landscape")
    pdf.set_auto_page_break(auto=True, margin=12)
    pdf.add_page()
    pdf.image(HEADER, x=10, y=8, w=259)
    pdf.set_y(32)
    pdf.set_font("Helvetica", "B", 12)
    pdf.cell(0, 7, _safe(name or "Search results"), new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 9)
    pdf.set_text_color(80, 80, 80)
    printed = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M %Z")
    pdf.cell(0, 5, _safe(f"{len(cases)} cases  ·  Printed {printed}"), new_x="LMARGIN", new_y="NEXT")
    pdf.set_text_color(0, 0, 0)
    pdf.ln(2)
    columns = (
        ("court", "Court", 42),
        ("case_number", "Case", 30),
        ("division", "Division", 28),
        ("case_type", "Type / charge", 40),
        ("plaintiff", "Plaintiff", 36),
        ("defendant", "Defendant", 36),
        ("judgment", "Judgment", 24),
        ("hearing_date", "Hearing", 22),
    )
    _result_header(pdf, columns)
    pdf.set_font("Helvetica", "", 7)
    for case in cases:
        if pdf.get_y() > 198:
            pdf.add_page()
            _result_header(pdf, columns)
            pdf.set_font("Helvetica", "", 7)
        pdf.set_x(pdf.l_margin)
        for key, _label, width in columns:
            pdf.cell(width, 5, _fit(case.get(key, ""), width), border=0)
        pdf.ln(5)
    return bytes(pdf.output())


def _result_header(pdf, columns):
    pdf.set_x(pdf.l_margin)
    pdf.set_font("Helvetica", "B", 7)
    pdf.set_fill_color(245, 245, 244)
    for _key, label, width in columns:
        pdf.cell(width, 6, _safe(label), fill=True)
    pdf.ln(6)


def _fit(value, width):
    text = _safe(value)
    # 7pt Helvetica is about 1.8mm per character. Keep the cell on one line.
    limit = max(4, int(width / 1.7))
    return text if len(text) <= limit else text[:limit - 1] + "."


def _table(pdf, table):
    columns = table.get("columns") or []
    rows = table.get("rows") or []
    if not columns or not rows:
        return
    pdf.ln(3)
    pdf.set_x(pdf.l_margin)
    pdf.set_font("Helvetica", "B", 8)
    pdf.set_fill_color(245, 245, 244)
    width = 186 / len(columns)
    for column in columns:
        pdf.cell(width, 6, _safe(column)[:22], border=0, fill=True)
    pdf.ln(6)
    pdf.set_font("Helvetica", "", 8)
    for row in rows:
        pdf.set_x(pdf.l_margin)
        for column in columns:
            pdf.cell(width, 5, _safe(row.get(column, ""))[:28])
        pdf.ln(5)


def _safe(value):
    text = "" if value is None else str(value)
    return text.encode("latin-1", "replace").decode("latin-1")
