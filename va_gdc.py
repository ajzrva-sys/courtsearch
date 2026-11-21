"""Client for the Virginia General District Court Online Case Information System.

The public portal (eapps.courts.state.va.us/gdcourts) can only search one court
per request. This client accepts the site's terms once per session, selects a
court by its FIPS code, and runs the same name search a person would, for both
the civil division and the criminal/traffic division.

Observed request flow (build 6.4.1.2):

    GET  /gdcourts/landing.do                       terms page
    POST /gdcourts/landing.do            accept=Accept
    POST /gdcourts/nameSearch.do         formAction=newSearch, searchFipsCode=<fips>

The name-search post carries the court and the division, so one session can
search every court after accepting the terms once. changeCourt.do is only
needed before opening a case detail page.

Name searches return every case type the division holds: unlawful detainers,
warrants in debt, garnishments, and on the criminal/traffic side every charge.
Results come back 20 rows at a time; this client follows the portal's own
Next control rather than guessing case numbers.
"""

import re
import time
from dataclasses import dataclass, field

import requests
from bs4 import BeautifulSoup

BASE = "https://eapps.courts.state.va.us/gdcourts"
USER_AGENT = "tenant-screen/0.1 (single-applicant court lookup; +contact on request)"

# searchDivision values the portal uses in its own sidebar links.
DIVISION_CIVIL = "V"
DIVISION_CRIMINAL = "T"
DIVISIONS = (DIVISION_CIVIL, DIVISION_CRIMINAL)

DIVISION_LABEL = {DIVISION_CIVIL: "Civil", DIVISION_CRIMINAL: "Criminal/Traffic"}

# Data status values from the portal's own dropdown.
STATUS_CURRENT = "A"    # filed or heard after January 2007
STATUS_ARCHIVED = "I"   # before January 2007
STATUS_ALL = "O"

PAGE_SIZE = 20


class PortalError(Exception):
    """The portal refused the request or returned something unparseable."""


@dataclass
class Case:
    court: str
    fips: str
    division: str
    case_number: str
    plaintiff: str
    defendant: str
    hearing_date: str
    hearing_time: str
    judgment: str
    case_type: str
    active: bool = True
    detail_url: str = ""

    def as_dict(self):
        return {
            "court": self.court,
            "fips": self.fips,
            "division": DIVISION_LABEL.get(self.division, self.division),
            "case_number": self.case_number,
            "plaintiff": self.plaintiff,
            "defendant": self.defendant,
            "hearing_date": self.hearing_date,
            "hearing_time": self.hearing_time,
            "judgment": self.judgment,
            "case_type": self.case_type,
            "detail_url": self.detail_url,
        }


@dataclass
class CourtResult:
    court: str
    fips: str
    division: str
    cases: list = field(default_factory=list)
    error: str = ""
    pages: int = 0


def _cell_text(cell):
    return re.sub(r"\s+", " ", cell.get_text(" ", strip=True)).strip()


def _row_columns(header, cells):
    """Map a result row onto header names.

    Civil rows are Case #, Plaintiff, Defendant, Hearing Date, Hearing Time,
    Case Judgment, Case Type. Criminal/traffic rows drop the plaintiff and
    carry Charge instead of Case Type. Reading by header keeps either layout
    from being mislabeled.
    """
    values = [_cell_text(cell) for cell in cells]
    mapped = {}
    for name, value in zip(header, values):
        if name:
            mapped[name] = value
    return mapped


def _detail_url(link, fips):
    """Criminal rows open details through caseDetailsPayment() rather than an href."""
    onclick = link.get("onclick") or ""
    match = re.search(r"caseDetailsPayment\(\s*'(\w+)'\s*,\s*'([^']+)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'", onclick)
    if not match:
        return ""
    action, case_number, court_fips, active = match.groups()
    return (f"{BASE}/nameSearch.do?formAction={action}&displayCaseNumber={case_number}"
            f"&localFipsCode={court_fips or fips}&caseActive={active}")


class GDCSession:
    """One accepted-terms session against the general district portal."""

    def __init__(self, pause=1.0, timeout=30):
        self.pause = pause
        self.session = requests.Session()
        self.session.headers["User-Agent"] = USER_AGENT
        self.timeout = timeout
        self._accepted = False
        self._selected = {}
        self._current = ("", "")

    def accept_terms(self):
        landing = self.session.get(f"{BASE}/landing.do", timeout=self.timeout)
        landing.raise_for_status()
        accepted = self.session.post(
            f"{BASE}/landing.do", data={"accept": "Accept"}, timeout=self.timeout,
            headers={"Referer": f"{BASE}/landing.do"},
        )
        accepted.raise_for_status()
        if "Terms and Conditions of Use" in accepted.text and "name=\"accept\"" in accepted.text:
            raise PortalError("the portal did not accept the terms acknowledgement")
        self._accepted = True

    def _ensure(self):
        if not self._accepted:
            self.accept_terms()

    def select_court(self, court_name, fips, division):
        """Tell the portal which court this session is searching.

        The portal stores the choice in the session and bounces back to its
        welcome page; the search form then has to be opened for that court.
        """
        self._ensure()
        key = (fips, division)
        if self._selected.get(division) == fips:
            return
        self.session.get(
            f"{BASE}/nameSearch.do",
            params={
                "fromSidebar": "true", "formAction": "searchLanding",
                "searchDivision": division, "searchFipsCode": fips,
                "localFipsCode": fips,
            },
            timeout=self.timeout,
        )
        changed = self.session.post(
            f"{BASE}/changeCourt.do",
            data={
                "selectedCourtsName": court_name,
                "selectedCourtsFipCode": fips,
                "sessionCourtsFipCode": "",
            },
            timeout=self.timeout,
            headers={"Referer": f"{BASE}/nameSearch.do"},
        )
        changed.raise_for_status()
        self._selected[division] = fips
        self._current = (fips, division)

    def search_name(self, court_name, fips, division, last_name, first_name="",
                    middle_name="", suffix="", status=STATUS_ALL, max_pages=5):
        """Run one court's name search and follow its result pages.

        Returns a CourtResult. A portal or network failure is recorded on the
        result instead of raised, so one court's outage does not sink a search
        across the rest of the state.
        """
        result = CourtResult(court=court_name, fips=fips, division=division)
        try:
            # newSearch already names the court. Selecting it first is a second
            # round trip, and the portal answers without that wait.
            self._ensure()
            self._current = (fips, division)
            html = self._submit_search(last_name, first_name, middle_name, suffix, status)
            for page in range(max_pages):
                cases, next_payload = self._parse_results(html, court_name, fips, division)
                result.cases.extend(cases)
                result.pages = page + 1
                if not next_payload or len(cases) < PAGE_SIZE:
                    break
                time.sleep(self.pause)
                html = self._next_page(next_payload)
        except (requests.RequestException, PortalError) as exc:
            result.error = str(exc)
        return result

    def _submit_search(self, last_name, first_name, middle_name, suffix, status):
        # The portal's own submitForm() is what makes a search succeed, and it
        # does not do what its button label suggests. The Search button calls
        # submitForm('newSearch', '<fips>'), which posts formAction=newSearch,
        # copies the typed name into the hidden lastName/firstName fields, and
        # sets both localFipsCode and searchFipsCode to the selected court.
        # A POST with formAction=search comes back with an empty body.
        fips, division = self._current
        response = self.session.post(
            f"{BASE}/nameSearch.do",
            data={
                "formAction": "newSearch",
                "displayCaseNumber": "",
                "formBean": "",
                "localFipsCode": fips,
                "caseActive": "",
                "localLastName": "",
                "forward": "",
                "back": "",
                "localnamesearchlastName": last_name,
                "lastName": last_name,
                "localnamesearchfirstName": first_name,
                "firstName": first_name,
                "localnamesearchmiddleName": middle_name,
                "middleName": middle_name,
                "localnamesearchsuffix": suffix,
                "suffix": suffix,
                "localnamesearchsearchCategory": status,
                "searchCategory": status,
                "searchFipsCode": fips,
                "searchDivision": division,
                "searchType": "name",
                "clientSearchCounter": "1",
                "firstRowName": "",
                "firstRowCaseNumber": "",
                "lastRowName": "",
                "lastRowCaseNumber": "",
            },
            timeout=self.timeout,
            headers={"Referer": f"{BASE}/nameSearch.do"},
        )
        response.raise_for_status()
        if not response.text:
            raise PortalError("portal returned an empty response; the search was not accepted")
        return response.text

    def fetch_detail(self, court_name, fips, case_number, division):
        """Load one case's detail page through this session and parse it.

        The portal only serves that page inside the session that searched the
        court, which is why opening the link in a browser fails. Callers store
        the parsed result and never send the user to the portal.
        """
        self.select_court(court_name, fips, division)
        response = self.session.get(
            f"{BASE}/nameSearch.do",
            params={
                "formAction": "caseDetails",
                "displayCaseNumber": case_number,
                "localFipsCode": fips,
                "caseActive": "true",
            },
            timeout=self.timeout,
            headers={"Referer": f"{BASE}/nameSearch.do"},
        )
        response.raise_for_status()
        if not response.text or "Case Number" not in response.text:
            raise PortalError("the portal did not return a case detail page")
        return parse_case_detail(response.text)

    def _next_page(self, payload):
        response = self.session.post(
            f"{BASE}/nameSearch.do", data=payload, timeout=self.timeout,
            headers={"Referer": f"{BASE}/nameSearch.do"},
        )
        response.raise_for_status()
        return response.text

    def _parse_results(self, html, court_name, fips, division):
        if "Terms and Conditions of Use" in html and "name=\"accept\"" in html:
            self._accepted = False
            raise PortalError("session expired; terms must be accepted again")
        error = _search_error(html)
        if error:
            raise PortalError(error)

        soup = BeautifulSoup(html, "html.parser")
        table = soup.find("table", class_="tableborder")
        cases = []
        if table:
            header = [_cell_text(cell).lower() for cell in table.find("tr").find_all(["td", "th"])]
            for row in table.find_all("tr"):
                cells = row.find_all("td")
                link = row.find("a")
                if not link or len(cells) < 6:
                    continue
                case_number = _cell_text(cells[1])
                if not re.match(r"G[A-Z]\d", case_number):
                    continue
                columns = _row_columns(header, cells)
                href = link.get("href", "")
                if href.startswith("javascript:"):
                    href = ""
                cases.append(Case(
                    court=court_name,
                    fips=fips,
                    division=division,
                    case_number=case_number,
                    plaintiff=columns.get("plaintiff", ""),
                    defendant=columns.get("defendant", ""),
                    hearing_date=columns.get("hearing date", ""),
                    hearing_time=columns.get("hearing time", ""),
                    judgment=columns.get("case judgment", ""),
                    case_type=columns.get("case type") or columns.get("charge", ""),
                    active="caseActive=false" not in (link.get("onclick") or "") + href,
                    detail_url=f"{BASE}/{href}" if href else _detail_url(link, fips),
                ))
        return cases, _next_page_payload(soup)


def _search_error(html):
    match = re.search(r'id="searchError"[^>]*>(.*?)</(?:div|span|td)>', html, re.S)
    if not match:
        return ""
    text = re.sub(r"<[^>]+>", " ", match.group(1))
    text = re.sub(r"\s+", " ", text).strip()
    if not text or re.search(r"no (cases|records|results)|0 results|not found", text, re.I):
        return ""
    return text


def _next_page_payload(soup):
    """Read the portal's own Next control instead of inventing offsets.

    The results form carries firstRow/lastRow cursors and a forward flag.
    Only follow it when the page actually renders a Next submit.
    """
    form = None
    for candidate in soup.find_all("form"):
        if "nameSearch.do" in (candidate.get("action") or ""):
            form = candidate
            break
    if form is None or form.find("input", {"value": re.compile(r"^Next$", re.I)}) is None:
        return None
    payload = {}
    for element in form.find_all("input"):
        name = element.get("name")
        if name:
            payload[name] = element.get("value", "")
    payload["formAction"] = "next"
    payload["forward"] = "true"
    return payload


# Shown on the public case page, but not useful for a screening record and not
# something to copy into our cache.
_OMIT_FIELDS = {"dob", "date of birth", "gender", "race", "aka", "aka1", "aka2"}


def parse_case_detail(html):
    """Turn a portal case-detail page into sections we can store and show.

    Reads label/value pairs and the simple tables (parties, hearings, service).
    Drops date of birth, gender, and race even when the page includes them.
    """
    soup = BeautifulSoup(html, "html.parser")
    if "Terms and Conditions of Use" in html and 'name="accept"' in html:
        raise PortalError("session expired; terms must be accepted again")
    fields = {}
    tables = []
    for table in soup.find_all("table"):
        if table.find("table"):
            continue
        rows = [
            [_cell_text(cell) for cell in row.find_all("td")]
            for row in table.find_all("tr", recursive=False)
        ]
        rows = [row for row in rows if any(row)]
        if not rows:
            continue
        header = rows[0]
        if _is_header(header) and len(rows) > 1:
            records = []
            for row in rows[1:]:
                if row == header or not any(row):
                    continue
                records.append({
                    header[index]: row[index] if index < len(row) else ""
                    for index in range(len(header))
                })
            if records:
                tables.append({"columns": header, "rows": records})
            continue
        for row in rows:
            for index in range(0, len(row) - 1, 2):
                label = row[index].rstrip(":").strip()
                if not label.endswith(":") and not row[index].rstrip().endswith(":"):
                    continue
                label = row[index].replace("\xa0", " ").rstrip(":").strip()
                if label.lower() in _OMIT_FIELDS:
                    continue
                value = row[index + 1].strip()
                if label and label not in fields:
                    fields[label] = value
    return {"fields": fields, "tables": tables}


def _is_header(cells):
    if len(cells) < 2:
        return False
    if any(cell.endswith(":") or len(cell) > 40 for cell in cells):
        return False
    return all(cell[:1].isalpha() for cell in cells if cell)
