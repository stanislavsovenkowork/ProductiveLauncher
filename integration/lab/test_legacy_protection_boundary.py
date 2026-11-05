"""Historical optional experiment; not part of current website-blocking scope."""
import importlib.util
import json
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
class LegacyBoundaryTests(unittest.TestCase):
    def test_saved_lab_models_agreed_at_time_of_trial(self):
        spec=importlib.util.spec_from_file_location("legacy_boundary",Path(__file__).with_name("check_protection_boundary.py"))
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        result=module.compare(json.loads((ROOT/"integration/fixtures/mac-protection-window.json").read_text()),json.loads((ROOT/"integration/fixtures/android-protection-window.json").read_text()))
        self.assertTrue(result["matched"])
        self.assertFalse(result["liveSynchronized"])
