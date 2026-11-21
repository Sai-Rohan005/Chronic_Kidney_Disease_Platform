import importlib.util
from pathlib import Path
from decimal import Decimal
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("ratchet", Path(__file__).parents[1] / "ratchet_coverage.py")
ratchet = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ratchet)


class CoverageRatchetTests(unittest.TestCase):
    def test_report_is_rounded_down_without_inventing_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            report.write_text('<report><counter type="LINE" covered="7" missed="2"/><counter type="BRANCH" covered="1" missed="2"/></report>')
            self.assertEqual({"line": Decimal("0.77"), "branch": Decimal("0.33")}, ratchet.measured_floors(report))

    def test_ratchet_can_raise_but_never_lower_a_floor(self):
        pom = '<coverage.line.minimum>0.50</coverage.line.minimum><coverage.branch.minimum>0.60</coverage.branch.minimum>'
        updated = ratchet.raise_floors(pom, {"line": Decimal("0.70"), "branch": Decimal("0.20")})
        self.assertIn('<coverage.line.minimum>0.70</coverage.line.minimum>', updated)
        self.assertIn('<coverage.branch.minimum>0.60</coverage.branch.minimum>', updated)

    def test_missing_or_empty_counters_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            for body in ('<report/>', '<report><counter type="LINE" covered="0" missed="0"/></report>'):
                report.write_text(body)
                with self.assertRaises(ValueError):
                    ratchet.measured_floors(report)
