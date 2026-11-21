"""Local copy of cases returned by a search.

The court portal's case links only work inside its own session, so a result
row is stored here and the case page is served from this copy. A detail page,
when we have fetched one, is stored beside the search row. Date of birth,
gender, and race are not kept even if the portal page shows them.
"""

import json
import os
import sqlite3
import time
from datetime import datetime
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_PATH = os.path.join(HERE, "data", "cache.db")
# Filings are dated in Virginia. A search saved on an earlier court day is not
# an answer for today: a case filed after that search would stay hidden.
COURT_ZONE = ZoneInfo("America/New_York")

_SCHEMA = """
create table if not exists cases (
    fips text not null,
    case_number text not null,
    court text not null,
    division text not null,
    plaintiff text not null default '',
    defendant text not null default '',
    hearing_date text not null default '',
    hearing_time text not null default '',
    judgment text not null default '',
    case_type text not null default '',
    searched_name text not null default '',
    cached_at real not null,
    detail_json text not null default '',
    detail_at real not null default 0,
    primary key (fips, case_number)
)
"""


def _connect():
    os.makedirs(os.path.dirname(CACHE_PATH), exist_ok=True)
    connection = sqlite3.connect(CACHE_PATH, timeout=30)
    connection.row_factory = sqlite3.Row
    connection.execute("pragma journal_mode=wal")
    connection.execute(_SCHEMA)
    connection.execute(
        "create table if not exists searches ("
        "name_key text primary key, cached_at real not null, seconds real not null, "
        "summary_json text not null, cases_json text not null)"
    )
    connection.execute(
        "create table if not exists jobs ("
        "id text primary key, state text not null, done integer not null, "
        "total integer not null, last_court text not null default '', "
        "error text not null default '', started real not null, "
        "last_name text not null, first_name text not null, "
        "middle_initial text not null, results_json text not null default '')"
    )
    return connection


def store_cases(cases, searched_name):
    """Upsert the rows a search already returned. Does not touch a stored detail."""
    if not cases:
        return 0
    now = time.time()
    with _connect() as connection:
        for case in cases:
            connection.execute(
                """
                insert into cases (
                    fips, case_number, court, division, plaintiff, defendant,
                    hearing_date, hearing_time, judgment, case_type,
                    searched_name, cached_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(fips, case_number) do update set
                    court=excluded.court,
                    division=excluded.division,
                    plaintiff=excluded.plaintiff,
                    defendant=excluded.defendant,
                    hearing_date=excluded.hearing_date,
                    hearing_time=excluded.hearing_time,
                    judgment=excluded.judgment,
                    case_type=excluded.case_type,
                    searched_name=excluded.searched_name,
                    cached_at=excluded.cached_at
                """,
                (
                    case["fips"], case["case_number"], case["court"], case["division"],
                    case.get("plaintiff", ""), case.get("defendant", ""),
                    case.get("hearing_date", ""), case.get("hearing_time", ""),
                    case.get("judgment", ""), case.get("case_type", ""),
                    searched_name, now,
                ),
            )
    return len(cases)


def get_case(fips, case_number):
    with _connect() as connection:
        row = connection.execute(
            "select * from cases where fips = ? and case_number = ?",
            (fips, case_number),
        ).fetchone()
    if row is None:
        return None
    record = dict(row)
    record["detail"] = json.loads(record.pop("detail_json") or "null")
    return record


def store_detail(fips, case_number, detail):
    with _connect() as connection:
        updated = connection.execute(
            "update cases set detail_json = ?, detail_at = ? where fips = ? and case_number = ?",
            (json.dumps(detail), time.time(), fips, case_number),
        ).rowcount
    return updated == 1


def search_key(last_name, first_name="", middle_initial=""):
    return "|".join(part.upper() for part in (last_name, first_name or "", middle_initial or ""))


def store_search(last_name, first_name, middle_initial, results):
    """Save a finished name search so the same name can be shown again without the courts."""
    now = time.time()
    with _connect() as connection:
        connection.execute(
            """
            insert into searches (name_key, cached_at, seconds, summary_json, cases_json)
            values (?, ?, ?, ?, ?)
            on conflict(name_key) do update set
                cached_at=excluded.cached_at,
                seconds=excluded.seconds,
                summary_json=excluded.summary_json,
                cases_json=excluded.cases_json
            """,
            (
                search_key(last_name, first_name, middle_initial),
                now,
                results.get("seconds") or 0,
                json.dumps(results.get("summary") or {}),
                json.dumps(results.get("cases") or []),
            ),
        )
    return now


def saved_on_court_day(cached_at, now):
    """True when the save and `now` fall on the same Virginia court day."""
    saved_day = datetime.fromtimestamp(cached_at, COURT_ZONE).date()
    return saved_day == datetime.fromtimestamp(now, COURT_ZONE).date()


def get_search(last_name, first_name="", middle_initial="", now=None):
    """Return today's saved search, or None when the courts should be searched again.

    A save from a previous Virginia court day is left in the database and ignored.
    The next finished search overwrites it.
    """
    with _connect() as connection:
        row = connection.execute(
            "select cached_at, seconds, summary_json, cases_json from searches where name_key = ?",
            (search_key(last_name, first_name, middle_initial),),
        ).fetchone()
    if row is None:
        return None
    if not saved_on_court_day(row["cached_at"], time.time() if now is None else now):
        return None
    return {
        "summary": json.loads(row["summary_json"]),
        "cases": json.loads(row["cases_json"]),
        "seconds": row["seconds"],
        "cached_at": row["cached_at"],
        "from_cache": True,
    }


def backfill_searches():
    """Turn case rows saved before searches were stored into a reusable name search."""
    with _connect() as connection:
        names = [row[0] for row in connection.execute(
            "select distinct searched_name from cases where searched_name != ''"
        )]
        for name in names:
            parts = name.split()
            if len(parts) >= 3:
                first, middle, last = parts[0], parts[1], parts[-1]
            elif len(parts) == 2:
                first, middle, last = parts[0], "", parts[1]
            else:
                first, middle, last = "", "", parts[0]
            key = search_key(last, first, middle)
            if connection.execute("select 1 from searches where name_key = ?", (key,)).fetchone():
                continue
            saved = connection.execute(
                "select * from cases where searched_name = ?", (name,)
            ).fetchall()
            cases = []
            by_type = {}
            for row in saved:
                item = dict(row)
                item.pop("detail_json", None)
                item.pop("detail_at", None)
                item["href"] = f"/case/{item['fips']}/{item['case_number']}"
                cases.append(item)
                label = item.get("case_type") or "(unspecified)"
                by_type[label] = by_type.get(label, 0) + 1
            connection.execute(
                """
                insert into searches (name_key, cached_at, seconds, summary_json, cases_json)
                values (?, ?, ?, ?, ?)
                """,
                (
                    key,
                    max(row["cached_at"] for row in saved),
                    0,
                    json.dumps({
                        "courts_searched": len({row["fips"] for row in saved}),
                        "queries": len({row["fips"] for row in saved}),
                        "cases": len(cases),
                        "courts_with_cases": len({row["fips"] for row in saved}),
                        "failed_queries": 0,
                        "failures": [],
                        "by_case_type": by_type,
                    }),
                    json.dumps(cases),
                ),
            )


def save_job(job):
    """Persist a running search so another web worker can report its progress."""
    with _connect() as connection:
        connection.execute(
            """
            insert into jobs (
                id, state, done, total, last_court, error, started,
                last_name, first_name, middle_initial, results_json
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict(id) do update set
                state=excluded.state,
                done=excluded.done,
                total=excluded.total,
                last_court=excluded.last_court,
                error=excluded.error,
                results_json=excluded.results_json
            """,
            (
                job["id"], job["state"], job["done"], job["total"],
                job.get("last_court", ""), job.get("error", ""), job["started"],
                job["last_name"], job["first_name"], job["middle_initial"],
                json.dumps(job["results"]) if job.get("results") else "",
            ),
        )


def load_job(job_id):
    with _connect() as connection:
        row = connection.execute("select * from jobs where id = ?", (job_id,)).fetchone()
    if row is None:
        return None
    job = dict(row)
    job["results"] = json.loads(job.pop("results_json") or "null")
    return job


def expire_jobs(cutoff):
    with _connect() as connection:
        connection.execute("delete from jobs where started < ?", (cutoff,))
