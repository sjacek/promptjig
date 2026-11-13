# User guide publishing

This directory is the complete Jekyll source for the English Promptjig user
guide. User-facing pages are independent of the repository's development reports
in `docs/`. This file is excluded from the published site.

The site uses the existing Promptjig icon and a small responsive layout with no
remote fonts, JavaScript framework, tracking or external theme. Release numbers
and navigation live in `_config.yml`. Mark the Maven adapter as unreleased until
its actual publication; changing the producer's version alone is not publication.

## Build and validate locally

Use the same official image as the pinned `jekyll-build-pages` action. From the
repository root, with Docker running (PowerShell):

```powershell
docker run --rm --volume "${PWD}:/workspace" --env GITHUB_WORKSPACE=/workspace --env INPUT_SOURCE=docs/user-docs --env INPUT_DESTINATION=build/user-docs --env GITHUB_REPOSITORY=sjacek/promptjig --env GITHUB_API_URL=https://api.github.com ghcr.io/actions/jekyll-build-pages@sha256:6791ebfd912185ed59bfb5fb102664fa872496b79f87ff8b9cfba292a7345041
python -B -m unittest discover -s scripts -p test_user_docs.py -v
python -B scripts/validate_user_docs.py build/user-docs --baseurl /promptjig
```

For a local preview, serve the project URL prefix from a temporary parent folder:

```powershell
New-Item -ItemType Directory -Force build/pages-preview/promptjig | Out-Null
Copy-Item -Recurse -Force build/user-docs/* build/pages-preview/promptjig
python -m http.server 8000 --directory build/pages-preview
```

Open `http://localhost:8000/promptjig/`. Use a fresh preview folder when repeating
the copy, or serve the generated directory with a server that maps `/promptjig/`
to it. Generated output and Jekyll cache are ignored by Git.

## GitHub Pages

The `User documentation` workflow builds on relevant pushes and pull requests,
checks its validator and rendered links/anchors, then uploads a Pages artifact.
PRs, forks and non-main branches only build. Only `main` in `sjacek/promptjig`
can deploy, using the `github-pages` environment and job-scoped `pages: write`
and `id-token: write`. No publishing secret or PAT is required.

After these changes are merged to `main`, select **Settings → Pages → Build and
deployment → Source: GitHub Actions** in the repository. The workflow does not
change repository settings or bypass environment approval rules. The intended
address is `https://sjacek.github.io/promptjig/`; it is not claimed to be live
until a deployment succeeds. Manual runs on `main` can also publish.

See the official [Pages workflow documentation](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).
If the repository name or domain changes, update `_config.yml`, the workflow's
base URL validation argument and this preview path together.

## Editing

Pages need YAML front matter with `title` and an explicit `permalink`. Add new
pages to `navigation` in `_config.yml`. Use ordinary relative Markdown links,
such as `[first contract](getting-started.md)`. The bundled `jekyll-relative-links`
plugin converts them to permalinks with the `/promptjig` prefix during the build.
Use full HTTPS URLs for repository files outside this site. In layouts, keep
computed URLs behind `relative_url`; only their unresolved-target IDE inspection
is suppressed, since the rendered validator checks those paths. Use plain
headings for fragments and validate the rendered output rather than assuming
a hand-written slug.
Wrap GitHub Actions expressions in Liquid `raw` blocks inside Markdown examples.

Keep examples fictional and public. The first-contract walkthrough is a strict
three-field example; the bundled five-field runtime pilot explicitly demonstrates
repairing mode. Never imply that schema acceptance proves factual correctness.
