"""Statewide search across Virginia's general district courts.

One name goes in. Every court is searched through the judiciary's own per-court
portal, civil and criminal/traffic, and the results come back as one list.
"""

import json
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

from va_gdc import (
    DIVISION_CIVIL, DIVISION_CRIMINAL, DIVISION_LABEL, GDCSession, STATUS_ALL,
)

HERE = os.path.dirname(os.path.abspath(__file__))
COURTS_PATH = os.path.join(HERE, "data", "courts.json")


def load_courts():
    with open(COURTS_PATH) as handle:
        courts = json.load(handle)
    for court in courts:
        if not court.get("fips") or not court.get("name"):
            raise ValueError(f"court record missing name or fips: {court}")
    return courts


def search_all(last_name, first_name="", middle_name="", suffix="",
               divisions=(DIVISION_CIVIL, DIVISION_CRIMINAL),
               status=STATUS_ALL, courts=None, workers=8, pause=0,
               max_pages=5, progress=None):
    """Search every court for one name.

    Each worker keeps one accepted-terms session and reuses it across courts.
    A court that fails is tried once more on a new session. More than about
    eight workers at once makes the portal time out. `progress`, if given, is
    called with (done, total, court_result) as each court finishes.
    """
    if courts is None:
        courts = load_courts()
    jobs = [(court, division) for court in courts for division in divisions]
    total = len(jobs)
    results = []
    done = 0
    local = threading.local()
    sessions = []
    sessions_lock = threading.Lock()

    def session_for_thread():
        current = getattr(local, "session", None)
        if current is None:
            # A hung court used to hold a worker for the 30s client default.
            current = GDCSession(pause=pause, timeout=15)
            local.session = current
            with sessions_lock:
                sessions.append(current)
        return current

    def run(job):
        court, division = job
        result = None
        for attempt in range(2):
            result = session_for_thread().search_name(
                court["name"], court["fips"], division, last_name, first_name,
                middle_name, suffix, status=status, max_pages=max_pages,
            )
            if not result.error:
                return result
            local.session = None
            if attempt == 0:
                continue
        return result

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(run, job): job for job in jobs}
        for future in as_completed(futures):
            court_result = future.result()
            results.append(court_result)
            done += 1
            if progress:
                progress(done, total, court_result)
    for item in sessions:
        item.session.close()
    results.sort(key=lambda item: (item.court, item.division))
    return results


def flatten(results, defendant_only=False, name=""):
    """One row per case. defendant_only drops cases where the name is plaintiff-side only."""
    rows = []
    for court_result in results:
        for case in court_result.cases:
            if defendant_only and name and not _named_as_defendant(case, name):
                continue
            rows.append(case.as_dict())
    rows.sort(key=_hearing_sort, reverse=True)
    return rows


def summarize(results):
    cases = [case for court_result in results for case in court_result.cases]
    failures = [court_result for court_result in results if court_result.error]
    by_type = {}
    for case in cases:
        by_type[case.case_type or "(unspecified)"] = by_type.get(case.case_type or "(unspecified)", 0) + 1
    return {
        "courts_searched": len({item.fips for item in results}),
        "queries": len(results),
        "cases": len(cases),
        "courts_with_cases": len({item.fips for item in results if item.cases}),
        "failed_queries": len(failures),
        "failures": [
            {"court": item.court, "division": DIVISION_LABEL.get(item.division, item.division),
             "error": item.error}
            for item in failures
        ],
        "by_case_type": dict(sorted(by_type.items(), key=lambda pair: -pair[1])),
    }


def _named_as_defendant(case, name):
    target = " ".join(name.upper().split())
    for party in case.defendant.split(";"):
        if target in " ".join(party.upper().split()):
            return True
    return False


def _hearing_sort(row):
    date = row.get("hearing_date", "")
    parts = date.split("/")
    if len(parts) == 3 and all(part.isdigit() for part in parts):
        month, day, year = parts
        return f"{year}{month.zfill(2)}{day.zfill(2)}"
    return ""


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Search every Virginia general district court for one name.")
    parser.add_argument("last_name")
    parser.add_argument("first_name", nargs="?", default="")
    parser.add_argument("--courts", type=int, default=0, help="limit to the first N courts (for a smoke test)")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--pause", type=float, default=0)
    args = parser.parse_args()

    courts = load_courts()
    if args.courts:
        courts = courts[:args.courts]
    started = time.time()

    def show(done, total, court_result):
        status = f"{len(court_result.cases)} cases" if not court_result.error else f"FAILED {court_result.error}"
        print(f"[{done}/{total}] {court_result.court} ({DIVISION_LABEL.get(court_result.division, '')}): {status}",
              flush=True)

    found = search_all(args.last_name, args.first_name, courts=courts,
                       workers=args.workers, pause=args.pause, progress=show)
    print(json.dumps({"summary": summarize(found), "seconds": round(time.time() - started, 1),
                      "cases": flatten(found)}, indent=1))
