"""Regression checks for the documentation pages repaired after the link audit.

Run after npm run docs:build with:
python3 -B -m unittest discover -s .github/scripts -p 'test_docs_links.py'
"""

from html.parser import HTMLParser
from pathlib import Path
import unittest
from urllib.parse import unquote


DIST = Path(__file__).resolve().parents[2] / "docs" / ".vuepress" / "dist"
PAGES = ("ai-agents", "pentest-checks", "security-checks")


class PageLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.fragments = set()
        self.headings = set()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            self.ids.add(attrs["id"])
            if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
                self.headings.add(attrs["id"])
        if tag == "a" and attrs.get("href", "").startswith("#"):
            fragment = unquote(attrs["href"][1:])
            if fragment:
                self.fragments.add(fragment)


class DocumentationLinksTests(unittest.TestCase):
    def read_page(self, name):
        page = PageLinks()
        page.feed((DIST / f"{name}.html").read_text(encoding="utf-8"))
        self.assertTrue(page.headings, f"{name}: generated page has no headings")
        self.assertTrue(page.fragments, f"{name}: generated page has no fragment links")
        return page

    def test_same_page_links_resolve_in_generated_html(self):
        for name in PAGES:
            with self.subTest(page=name):
                page = self.read_page(name)
                self.assertEqual(page.fragments - page.ids, set())

    def test_repaired_targets_are_linked_headings(self):
        targets = {
            "ai-agents": {"coffilot-bootui-in-the-github-copilot-app-s-side-panel"},
            "pentest-checks": {
                "platform-specific-metadata-coverage",
                "bootui-s-own-actuator-defaults-are-never-flagged",
            },
        }
        for name, expected in targets.items():
            with self.subTest(page=name):
                page = self.read_page(name)
                self.assertLessEqual(expected, page.headings)
                self.assertLessEqual(expected, page.fragments)


if __name__ == "__main__":
    unittest.main()
