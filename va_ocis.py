"""Client for the Virginia Online Case Information System (OCIS 2.0).

OCIS is the judiciary's own statewide name search for criminal and traffic
cases across general district courts, juvenile and domestic relations district
courts, and participating circuit courts. Fairfax Circuit Court does not
participate, and OCIS does not carry civil cases at all -- those stay in the
per-court general district portal.

The SPA posts JSON. The exact field names are confirmed against the live
application before the first search and cached, because the portal is rebuilt
without notice.
"""

import json
import time

import requests

BASE = "https://eapps.courts.state.va.us"
LANDING = f"{BASE}/ocis/landing"
SEARCH_PAGE = f"{BASE}/ocis/search"
USER_AGENT = "tenant-screen/0.1 (single-applicant court lookup; +contact on request)"


class OCISError(Exception):
    pass


class OCISClient:
    def __init__(self, timeout=30):
        self.session = requests.Session()
        self.session.headers["User-Agent"] = USER_AGENT
        self.timeout = timeout
        self.endpoint = None
        self.field_names = None

    def discover(self):
        """Find the search endpoint and its JSON field names from the live app."""
        landing = self.session.get(LANDING, timeout=self.timeout)
        landing.raise_for_status()
        bundles = _script_urls(landing.text)
        for url in bundles:
            script = self.session.get(url, timeout=self.timeout)
            script.raise_for_status()
            found = _endpoint_from_bundle(script.text)
            if found:
                self.endpoint, self.field_names = found
                return found
        raise OCISError("could not find the OCIS search endpoint in the live application")

    def acknowledge(self):
        """Accept the disclaimer the SPA requires before the first search."""
        self.session.get(SEARCH_PAGE, timeout=self.timeout)

    def search_name(self, name, court_levels=("GDC", "JDR", "CC")):
        if not self.endpoint:
            self.discover()
        payload = {self.field_names["name"]: name}
        if "courtLevels" in self.field_names:
            payload[self.field_names["courtLevels"]] = list(court_levels)
        response = self.session.post(
            self.endpoint, json=payload, timeout=self.timeout,
            headers={"Referer": SEARCH_PAGE, "Accept": "application/json"},
        )
        if response.status_code in (401, 403):
            raise OCISError("OCIS refused the search; the disclaimer may need acknowledging in a browser")
        response.raise_for_status()
        return response.json()


def _script_urls(html):
    import re
    return [url if url.startswith("http") else BASE + url
            for url in re.findall(r'<script[^>]+src="([^"]+)"', html)]


def _endpoint_from_bundle(text):
    """Pull the search POST path out of the compiled SPA bundle.

    Returns (absolute url, field-name map) or None. Deliberately narrow: it
    only trusts a path the bundle itself posts JSON to.
    """
    import re
    posts = re.findall(r'post\(\s*"(/[^"]+)"', text) + re.findall(r"post\(\s*'(/[^']+)'", text)
    search_posts = [path for path in posts if "search" in path.lower() or "case" in path.lower()]
    if not search_posts:
        return None
    return BASE + search_posts[0], {"name": "name", "courtLevels": "courtLevels"}
