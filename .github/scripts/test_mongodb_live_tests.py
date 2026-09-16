import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("mongodb_gate", Path(__file__).with_name("check-mongodb-live-tests.py"))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class MongoDbLiveEvidenceTests(unittest.TestCase):
    def check(self, xml=None, since=0):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            if xml is not None:
                (path / "TEST-fixture.xml").write_text(xml, encoding="utf-8")
            return GATE.check(path, ["MongoDbLiveTests"], since)

    def test_requires_report(self):
        self.assertTrue(self.check())

    def test_requires_executed_cases(self):
        self.assertTrue(self.check('<testsuite name="MongoDbLiveTests" tests="0"/>'))

    def test_rejects_skips_even_with_false_zero_counter(self):
        self.assertTrue(self.check('<testsuite name="MongoDbLiveTests" tests="1"><testcase name="real"><skipped/></testcase></testsuite>'))

    def test_rejects_errors_and_inconsistent_counts(self):
        self.assertTrue(self.check('<testsuite name="MongoDbLiveTests" tests="2"><testcase name="real"/></testsuite>'))
        self.assertTrue(self.check('<testsuite name="MongoDbLiveTests" tests="1"><testcase name="real"><error/></testcase></testsuite>'))

    def test_rejects_stale_and_malformed(self):
        self.assertTrue(self.check('<testsuite name="MongoDbLiveTests" tests="1"><testcase name="real"/></testsuite>', 9_999_999_999))
        self.assertTrue(self.check("<invalid"))

    def test_accepts_real_success(self):
        self.assertEqual([], self.check('<testsuite name="MongoDbLiveTests" tests="1" failures="0" errors="0" skipped="0"><testcase name="real"/></testsuite>'))


if __name__ == "__main__":
    unittest.main()
