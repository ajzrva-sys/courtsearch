"""Entry point for Synology Web Station.

Web Station starts this file from its own profile directory, so the project
directory has to be current before the app imports its data files.
"""

import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
os.chdir(ROOT)
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app import app as application  # noqa: E402
