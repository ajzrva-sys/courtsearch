"""JSON routes the Android client uses. The HTML pages stay beside them."""

import os
import tempfile
import unittest
from unittest.mock import patch

import app as web
import cache
from va_gdc import PortalError


CASE = {
    "fips": "001",
    "case_number": "GV26002576-00",
    "court": "Accomack General District Court",
    "division": "Civil",
    "plaintiff": "Landlord",
    "defendant": "Tenant",
    "hearing_date": "11/18/2026",
    "hearing_time": "09:00 AM",
    "judgment": "",
    "case_type": "Unlawful Detainer",
}


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.original = cache.CACHE_PATH
        cache.CACHE_PATH = os.path.join(self.tmp.name, "cache.db")
        self.client = web.app.test_client()

    def tearDown(self):
        cache.CACHE_PATH = self.original
        self.tmp.cleanup()

    def test_meta_lists_courts_and_screening_types(self):
        response = self.client.get("/api/meta")
        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertGreater(body["court_count"], 100)
        self.assertEqual(
            body["screening_types"],
            ["Unlawful Detainer", "Garnishment", "Warrant In Debt"],
        )

    def test_case_json_returns_a_stored_detail(self):
        cache.store_cases([CASE], "Jacqueline Wooden")
        cache.store_detail("001", "GV26002576-00", {
            "fields": {"Filed": "11/01/2026"},
            "tables": [{"columns": ["Hearing Date"], "rows": [{"Hearing Date": "11/18/2026"}]}],
        })

        with patch("app.GDCSession") as session:
            response = self.client.get("/api/case/001/GV26002576-00")
            session.assert_not_called()

        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertEqual(body["case_number"], "GV26002576-00")
        self.assertEqual(body["detail"]["fields"]["Filed"], "11/01/2026")
        self.assertEqual(body["detail_error"], "")
        self.assertNotIn("date of birth", str(body).lower())

    def test_case_page_still_renders_the_stored_case(self):
        cache.store_cases([CASE], "Jacqueline Wooden")
        cache.store_detail("001", "GV26002576-00", {"fields": {"Filed": "11/01/2026"}, "tables": []})

        response = self.client.get("/case/001/GV26002576-00")

        self.assertEqual(response.status_code, 200)
        self.assertIn(b"GV26002576-00", response.data)

    def test_case_json_reports_a_portal_failure(self):
        cache.store_cases([CASE], "Jacqueline Wooden")

        with patch("app.GDCSession") as session:
            session.return_value.fetch_detail.side_effect = PortalError("portal down")
            response = self.client.get("/api/case/001/GV26002576-00")

        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertIsNone(body["detail"])
        self.assertEqual(body["detail_error"], "portal down")

    def test_unknown_or_malformed_case_is_404(self):
        self.assertEqual(self.client.get("/api/case/01/GV26002576-00").status_code, 404)
        self.assertEqual(self.client.get("/api/case/001/not-a-case").status_code, 404)
        self.assertEqual(self.client.get("/api/case/001/GV26002576-00").status_code, 404)
        self.assertEqual(self.client.get("/case/001/not-a-case").status_code, 404)


if __name__ == "__main__":
    unittest.main()
