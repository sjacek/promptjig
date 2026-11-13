"""Rendered artifact checks must fail publication for broken navigation and anchors."""
from pathlib import Path
import tempfile
import unittest

from validate_user_docs import validate


class UserDocsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'guide').mkdir()
        (self.root / 'guide/index.html').write_text('<h1 id="setup">Setup</h1>', encoding='utf-8')
        (self.root / 'icon.svg').write_text('<svg/>', encoding='utf-8')

    def index(self, content):
        (self.root / 'index.html').write_text(content, encoding='utf-8')

    def test_project_prefix_relative_urls_assets_and_cross_page_anchors_resolve(self):
        self.index('<a href="/promptjig/guide/#setup">Guide</a>'
                   '<a href="guide/#setup">Relative</a><img src="/promptjig/icon.svg">'
                   '<a href="https://example.invalid/">External URLs are not fetched</a>')
        validate(self.root)

    def test_empty_artifact_cannot_pass(self):
        with self.assertRaisesRegex(ValueError, 'no index.html'):
            validate(self.root)

    def test_missing_pages_assets_and_fragments_fail(self):
        for link in ('/promptjig/missing/', '/promptjig/missing.svg', '/promptjig/guide/#unknown'):
            with self.subTest(link=link):
                self.index('<a href="' + link + '">Broken</a>')
                with self.assertRaisesRegex(ValueError, 'missing (target|anchor)'):
                    validate(self.root)

    def test_links_escaping_artifact_or_missing_project_prefix_fail(self):
        for link in ('/guide/', '../outside.html', '/promptjig/%2e%2e/outside.html'):
            with self.subTest(link=link):
                self.index('<a href="' + link + '">Broken</a>')
                with self.assertRaisesRegex(ValueError, 'prefix|escapes artifact'):
                    validate(self.root)

    def test_duplicate_ids_and_executable_link_schemes_fail(self):
        for text in ('<h1 id="same">One</h1><h2 id="same">Two</h2>',
                     '<a href="javascript:alert(1)">Invalid</a>'):
            with self.subTest(text=text):
                self.index(text)
                with self.assertRaisesRegex(ValueError, 'duplicate anchor|unsupported link scheme'):
                    validate(self.root)

    def test_root_hosted_preview_and_same_page_fragments_resolve(self):
        self.index('<h1 id="start">Start</h1><a href="#start">Start</a><a href="/guide/#setup">Guide</a>')
        validate(self.root, baseurl='')


if __name__ == '__main__':
    unittest.main()
