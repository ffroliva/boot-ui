#!/usr/bin/env python3
"""Require fresh, successful MongoDB evidence; missing/disabled Docker cannot pass."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def check(reports: Path, expected: list[str], since: float = 0) -> list[str]:
    failures = []
    suites = {}
    for report in sorted(reports.glob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
        except (OSError, ET.ParseError) as error:
            failures.append(f"{report.name}: unreadable report ({type(error).__name__})")
            continue
        for suite in [root] if root.tag == "testsuite" else root.iter("testsuite"):
            name = suite.get("name", "").rsplit(".", 1)[-1]
            if name in expected:
                suites.setdefault(name, []).append((report, suite))
    for name in expected:
        matches = suites.get(name, [])
        if len(matches) != 1:
            failures.append(f"{name}: expected exactly one current report, found {len(matches)}")
            continue
        report, suite = matches[0]
        if report.stat().st_mtime < since:
            failures.append(f"{name}: stale report")
        try:
            counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        except ValueError:
            failures.append(f"{name}: malformed counters")
            continue
        cases = list(suite.iter("testcase"))
        if counts["tests"] <= 0 or counts["tests"] != len(cases):
            failures.append(f"{name}: no executed cases or inconsistent counters")
        if any(counts[key] for key in ("failures", "errors", "skipped")) or any(
            case.find(state) is not None for case in cases for state in ("failure", "error", "skipped")
        ):
            failures.append(f"{name}: required scenarios must all pass without skips")
        if len({(case.get("classname"), case.get("name")) for case in cases}) != len(cases):
            failures.append(f"{name}: duplicate test cases")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path)
    parser.add_argument("suites", nargs="+")
    parser.add_argument("--since", type=float, default=0, help="Lane start time as Unix seconds")
    arguments = parser.parse_args()
    failures = check(arguments.reports, arguments.suites, arguments.since)
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"MongoDB live evidence: {len(arguments.suites)} required suites passed without skips.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
