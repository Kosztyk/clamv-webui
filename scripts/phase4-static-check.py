#!/usr/bin/env python3
"""Compatibility wrapper: use scripts/check-monitoring-inventory.py for new CI jobs."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).with_name("check-monitoring-inventory.py")), run_name="__main__")
