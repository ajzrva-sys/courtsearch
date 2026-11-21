"""Saved name searches are answers only on the Virginia court day they were run."""

import os
import tempfile
import unittest
from datetime import datetime

import cache


class SavedSearchTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.original = cache.CACHE_PATH
        cache.CACHE_PATH = os.path.join(self.tmp.name, "cache.db")

    def tearDown(self):
        cache.CACHE_PATH = self.original
        self.tmp.cleanup()

    def test_search_from_a_previous_court_day_is_not_reused(self):
        cache.store_search("Wooden", "Jacqueline", "", {
            "seconds": 40, "summary": {"cases": 0}, "cases": [],
        })
        stale = datetime(2026, 11, 14, 8, 29, 4, tzinfo=cache.COURT_ZONE).timestamp()
        now = datetime(2026, 11, 18, 9, 0, tzinfo=cache.COURT_ZONE).timestamp()
        with cache._connect() as connection:
            connection.execute("update searches set cached_at = ?", (stale,))

        self.assertIsNone(cache.get_search("Wooden", "Jacqueline", "", now=now))

    def test_search_from_earlier_today_is_reused(self):
        cache.store_search("Wooden", "Jacqueline", "", {
            "seconds": 40,
            "summary": {"cases": 1},
            "cases": [{"case_number": "GV26002576-00"}],
        })
        saved = datetime(2026, 11, 18, 8, 15, tzinfo=cache.COURT_ZONE).timestamp()
        now = datetime(2026, 11, 18, 16, 0, tzinfo=cache.COURT_ZONE).timestamp()
        with cache._connect() as connection:
            connection.execute("update searches set cached_at = ?", (saved,))

        found = cache.get_search("Wooden", "Jacqueline", "", now=now)
        self.assertEqual(found["cases"][0]["case_number"], "GV26002576-00")
        self.assertTrue(found["from_cache"])


if __name__ == "__main__":
    unittest.main()
