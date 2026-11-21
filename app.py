"""Web interface for a statewide Virginia general district court name search."""

import io
import os
import re
import threading
import time
import uuid

from flask import Flask, abort, jsonify, render_template, request, send_file

import requests

from cache import (
    backfill_searches, expire_jobs, get_case, get_search, load_job,
    save_job, store_cases, store_detail, store_search,
)
from report import render_case_pdf, render_results_pdf
from search import flatten, load_courts, search_all, summarize
from va_gdc import DIVISION_CIVIL, DIVISION_LABEL, GDCSession, PortalError

app = Flask(__name__)
app.config["TEMPLATES_AUTO_RELOAD"] = True

COURTS = load_courts()
backfill_searches()
JOB_TTL_SECONDS = 3600

# Case types a landlord screening decision turns on. Shown as a filter, never
# applied silently: the full result set includes every case type the portal returns.
SCREENING_TYPES = ("Unlawful Detainer", "Garnishment", "Warrant In Debt")


def _clean_name(value, *, initial=False):
    value = re.sub(r"\s+", " ", (value or "").strip())
    if not value:
        return ""
    pattern = r"[A-Za-z]" if initial else r"[A-Za-z][A-Za-z .'\-]{0,39}"
    if not re.fullmatch(pattern, value):
        return ""
    return value


@app.route("/")
def index():
    return render_template("index.html", court_count=len(COURTS), screening_types=SCREENING_TYPES)


@app.route("/api/meta")
def meta():
    return jsonify({"court_count": len(COURTS), "screening_types": list(SCREENING_TYPES)})


@app.route("/api/search", methods=["POST"])
def start_search():
    payload = request.get_json(force=True, silent=True) or {}
    last_name = _clean_name(payload.get("last_name"))
    first_name = _clean_name(payload.get("first_name")) if payload.get("first_name") else ""
    middle = _clean_name(payload.get("middle_initial"), initial=True) if payload.get("middle_initial") else ""
    if not last_name:
        return jsonify({"error": "Enter a last name using letters only."}), 400
    if payload.get("first_name") and not first_name:
        return jsonify({"error": "First name must contain only letters."}), 400
    if payload.get("middle_initial") and not middle:
        return jsonify({"error": "Middle initial must be a single letter."}), 400

    refresh = bool(payload.get("refresh"))
    saved = None if refresh else get_search(last_name, first_name, middle)
    if saved:
        return jsonify({"cached": True, "results": saved})

    job_id = uuid.uuid4().hex
    job = {
        "id": job_id,
        "last_name": last_name,
        "first_name": first_name,
        "middle_initial": middle,
        "state": "running",
        "done": 0,
        "total": len(COURTS) * 2,
        "started": time.time(),
        "results": None,
        "error": "",
    }
    expire_jobs(time.time() - JOB_TTL_SECONDS)
    save_job(job)
    threading.Thread(target=_run, args=(job,), daemon=True).start()
    return jsonify({"job_id": job_id, "total": job["total"]})


def _run(job):
    def progress(done, total, court_result):
        job["done"] = done
        job["last_court"] = court_result.court
        save_job(job)

    try:
        results = search_all(job["last_name"], job["first_name"], job["middle_initial"],
                             courts=COURTS, workers=8, pause=0, max_pages=3, progress=progress)
        cases = flatten(results)
        for case in cases:
            case.pop("detail_url", None)
            case["href"] = f"/case/{case['fips']}/{case['case_number']}"
        searched = " ".join(part for part in (job["first_name"], job["middle_initial"], job["last_name"]) if part)
        store_cases(cases, searched)
        store_search(job["last_name"], job["first_name"], job["middle_initial"], {
            "summary": summarize(results),
            "cases": cases,
            "seconds": round(time.time() - job["started"], 1),
        })
        job["results"] = {
            "summary": summarize(results),
            "cases": cases,
            "seconds": round(time.time() - job["started"], 1),
            "cached_at": time.time(),
            "from_cache": False,
        }
        job["state"] = "done"
        save_job(job)
    except Exception as exc:  # one bad court is already captured per result
        job["error"] = str(exc)
        job["state"] = "failed"
        save_job(job)


@app.route("/api/search/<job_id>")
def search_status(job_id):
    job = load_job(job_id)
    if job is None:
        abort(404)
    body = {
        "state": job["state"],
        "done": job["done"],
        "total": job["total"],
        "last_court": job.get("last_court", ""),
        "error": job["error"],
    }
    if job["state"] == "done":
        body["results"] = job["results"]
    return jsonify(body)


@app.route("/api/print", methods=["POST"])
def print_results():
    payload = request.get_json(force=True, silent=True) or {}
    cases = payload.get("cases")
    if not isinstance(cases, list) or not cases:
        return jsonify({"error": "Nothing on the page to print."}), 400
    rows = []
    for case in cases[:5000]:
        if not isinstance(case, dict):
            continue
        rows.append({key: str(case.get(key) or "")[:120] for key in (
            "court", "case_number", "division", "case_type", "plaintiff",
            "defendant", "judgment", "hearing_date",
        )})
    if not rows:
        return jsonify({"error": "Nothing on the page to print."}), 400
    name = re.sub(r"\s+", " ", str(payload.get("name") or "")).strip()[:80]
    pdf = render_results_pdf(name, rows)
    filename = (name or "results").replace(" ", "-") + ".pdf"
    return send_file(
        io.BytesIO(pdf), mimetype="application/pdf", as_attachment=True, download_name=filename,
    )


_DIVISION_CODE = {label: code for code, label in DIVISION_LABEL.items()}


@app.route("/api/case/<fips>/<case_number>")
def case_json(fips, case_number):
    record, detail_error = _case_record(fips, case_number)
    body = dict(record)
    body["detail_error"] = detail_error
    return jsonify(body)


@app.route("/case/<fips>/<case_number>")
def case_page(fips, case_number):
    record, detail_error = _case_record(fips, case_number)
    return render_template("case.html", case=record, detail_error=detail_error)


@app.route("/case/<fips>/<case_number>/pdf")
def case_pdf(fips, case_number):
    record, _detail_error = _case_record(fips, case_number)
    pdf = render_case_pdf(record)
    filename = f"{record['court'].split()[0]}-{record['case_number']}.pdf"
    return send_file(
        io.BytesIO(pdf), mimetype="application/pdf", as_attachment=True, download_name=filename,
    )


def _case_record(fips, case_number):
    if not re.fullmatch(r"\d{3}", fips) or not re.fullmatch(r"G[A-Z]\d{8}-\d{2}", case_number):
        abort(404)
    record = get_case(fips, case_number)
    if record is None:
        abort(404)
    detail_error = ""
    if not record["detail"]:
        division = _DIVISION_CODE.get(record["division"], DIVISION_CIVIL)
        try:
            record["detail"] = GDCSession(pause=0.3).fetch_detail(
                record["court"], fips, case_number, division,
            )
            store_detail(fips, case_number, record["detail"])
        except (PortalError, OSError, requests.RequestException) as exc:
            detail_error = str(exc)
    return record, detail_error


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=int(os.environ.get("PORT", "5055")), debug=False)
