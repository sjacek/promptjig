"""Check the rendered Pages artifact, including project-prefix URLs and cross-page anchors."""
import argparse
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit


class Document(HTMLParser):
    """Collect rendered IDs and links; checking source Markdown misses generated heading anchors."""
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.ids = set()
        self.links = []
        self.duplicates = []
        self.feed(text)

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        identity = attributes.get('id')
        if identity:
            if identity in self.ids:
                self.duplicates.append(identity)
            self.ids.add(identity)
        if tag == 'a' and attributes.get('name'):
            self.ids.add(attributes['name'])
        for name in ('href', 'src'):
            if attributes.get(name):
                self.links.append(attributes[name])


def validate(directory, baseurl='/promptjig'):
    """Reject missing pages/assets, broken fragments and paths escaping the published artifact."""
    root = Path(directory).resolve()
    if not (root / 'index.html').is_file():
        raise ValueError('Documentation artifact has no index.html')
    baseurl = baseurl.rstrip('/')
    if baseurl and (not baseurl.startswith('/') or '..' in baseurl.split('/')):
        raise ValueError('Invalid site base URL')
    pages = {path: Document(path.read_text(encoding='utf-8')) for path in root.rglob('*.html')}
    failures = []
    checked = 0
    for page, document in pages.items():
        source = page.relative_to(root).as_posix()
        failures.extend(source + ': duplicate anchor #' + name for name in document.duplicates)
        for value in document.links:
            link = urlsplit(value)
            if link.scheme or link.netloc:
                if link.scheme.lower() not in ('https', 'http', 'mailto', 'tel', ''):
                    failures.append(source + ': unsupported link scheme: ' + value)
                continue
            checked += 1
            path = unquote(link.path)
            if path.startswith('/'):
                if baseurl and path != baseurl and not path.startswith(baseurl + '/'):
                    failures.append(source + ': link omits site prefix: ' + value)
                    continue
                target = root / path[len(baseurl):].lstrip('/')
            else:
                target = page.parent / path if path else page
            target = target.resolve()
            if not target.is_relative_to(root):
                failures.append(source + ': link escapes artifact: ' + value)
                continue
            if target.is_dir():
                target /= 'index.html'
            if not target.is_file():
                failures.append(source + ': missing target: ' + value)
                continue
            if link.fragment:
                fragment = unquote(link.fragment)
                destination = pages.get(target)
                if destination is None or fragment not in destination.ids:
                    failures.append(source + ': missing anchor: ' + value)
    if failures:
        raise ValueError('\n'.join(failures))
    print(f'PASS: {len(pages)} rendered HTML pages and {checked} internal links/assets')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--baseurl', default='/promptjig')
    args = parser.parse_args()
    try:
        validate(args.directory, args.baseurl)
    except ValueError as failure:
        parser.exit(1, str(failure) + '\n')


if __name__ == '__main__':
    main()
