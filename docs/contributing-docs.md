# Building, running, and deploying the docs

This page is for contributors who edit the catalogue itself. The runtime users of Mongoose Plugins can skip it.

## Stack

| Component         | Version          | Purpose                          |
|-------------------|------------------|----------------------------------|
| MkDocs            | 1.6.x            | Static site generator            |
| Material for MkDocs | 9.5.x          | Theme + grid-cards + tabs        |
| pymdown-extensions | 10.14.x         | Tabbed code, admonitions, etc.   |
| Pygments          | 2.18.x           | Syntax highlighting              |
| Python            | 3.10+            | Build runtime                    |

All locked in `docs-requirements.txt`.

## One-shot local build

From the repo root:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r docs-requirements.txt

mkdocs build --strict
```

The site lands in `site/`. Open `site/index.html` to inspect, or serve it (next section).

`--strict` turns broken-link / nav-mismatch warnings into errors — same flag CI uses.

## Live-reload dev server

```bash
source .venv/bin/activate
mkdocs serve
```

Then browse to <http://127.0.0.1:8000>. The server watches `docs/`, `mkdocs.yml`, and `docs/stylesheets/extra.css` — saves re-render the page in your browser within a second.

Bind to a different port or host:

```bash
mkdocs serve --dev-addr 0.0.0.0:9000
```

## Repository layout

```
mongoose-plugins/
├── mkdocs.yml                     ← site config + nav tree
├── docs-requirements.txt          ← pinned Python deps
├── docs/
│   ├── index.md                   ← landing
│   ├── getting-started.md
│   ├── architecture.md
│   ├── operations.md
│   ├── contributing-docs.md       ← this page
│   ├── stylesheets/
│   │   └── extra.css              ← cards, tags, hero styling
│   ├── connectors/
│   │   ├── index.md
│   │   ├── aeron.md
│   │   ├── chronicle.md
│   │   ├── file.md
│   │   ├── kafka.md
│   │   └── multicast.md
│   ├── services/
│   │   ├── index.md
│   │   ├── admin-rest.md
│   │   ├── admin-telnet.md
│   │   ├── cache.md
│   │   ├── jdbc.md
│   │   ├── loader-yaml.md
│   │   └── loader-spring.md
│   └── libraries/
│       ├── index.md
│       └── jsonserialiser.md
├── site/                          ← build output (gitignored)
└── .github/workflows/docs.yml     ← CI deploy
```

## Editorial conventions

- **One page per plugin.** Mirror the structure of `docs/connectors/aeron.md`: tag bar, dependency snippet, samples in tabs, config table, operational notes, source link.
- **Tags** are stylable classes — pick from `source`, `sink`, `service`, `library`, `lowlat`, `replay`, `broker`, `persist`. Add new ones to `extra.css` if you need them.
- **Code samples** use fenced blocks with explicit language hints (` ```yaml `, ` ```java `, ` ```bash `).
- **Tabbed alternates** (YAML / Java / Spring XML for the same config) use `pymdownx.tabbed`:
  ```markdown
  === "YAML"
      ```yaml
      ...
      ```

  === "Java"
      ```java
      ...
      ```
  ```
- **Admonitions** (`!!! tip`, `!!! warning`) are sparing — only when the reader is likely to make a mistake.

## Adding a new plugin to the catalogue

1. Drop a new page under `docs/connectors/<name>.md` (or `services/`, `libraries/`).
2. Register it in the nav tree in `mkdocs.yml` under the matching section.
3. Add a card to `docs/index.md` under the right `<div class="grid cards">` block.
4. Add a card to the category index page (`docs/connectors/index.md` etc.).
5. Run `mkdocs build --strict` to confirm no broken links.

## Deploying to GitHub Pages

### Automated (CI)

Every push to `main` that touches `docs/`, `mkdocs.yml`, or the workflow itself triggers `.github/workflows/docs.yml`. It:

1. Checks out the repo with full history (needed by `mkdocs gh-deploy`).
2. Sets up Python 3, caches `pip` against `docs-requirements.txt`.
3. Installs deps.
4. Runs `mkdocs build --strict` to fail-fast on any link/nav error.
5. Runs `mkdocs gh-deploy --force` to push the built site to the `gh-pages` branch.

GitHub Pages serves the `gh-pages` branch automatically — make sure that branch is selected in **Settings → Pages → Source** for the repo.

The workflow uses `permissions: contents: write` (no PAT needed) and runs on `ubuntu-latest`.

### Manual deploy

If you need to push the site immediately, from the repo root:

```bash
source .venv/bin/activate
mkdocs gh-deploy --force --message "Manual docs deploy"
```

This builds the site, commits it to the `gh-pages` branch, and pushes. You'll need write access to the repo.

`--force` overwrites the `gh-pages` branch history; the source `main` branch is unaffected.

### First-time setup

When deploying to a fresh repo:

1. Push the `mkdocs.yml`, `docs/`, and workflow to `main`.
2. Either let the CI run, or run `mkdocs gh-deploy --force` once locally to seed the `gh-pages` branch.
3. In GitHub: **Settings → Pages → Source → Deploy from a branch → `gh-pages` / `/ (root)`**.
4. Wait ~30 s for the first build. The catalogue lands at `https://<org>.github.io/<repo>/`.

For a custom domain, add a `docs/CNAME` file containing the domain name; `mkdocs gh-deploy` will copy it through.

## Troubleshooting

### `AttributeError: 'NoneType' object has no attribute 'replace'`

Pygments / pymdown-extensions version skew. Reinstall from the pinned `docs-requirements.txt`:

```bash
pip install --force-reinstall -r docs-requirements.txt
```

### `mkdocs build --strict` fails on broken link

Usually a stale entry in `mkdocs.yml` nav, or a `[link](target.md)` whose target you've renamed. Run without `--strict` to see all warnings at once:

```bash
mkdocs build
```

Fix each warning, then re-run with `--strict`.

### CSS edits don't appear in dev server

The dev server caches the CSS — hard-refresh the browser (Cmd-Shift-R on macOS, Ctrl-Shift-R elsewhere) or restart `mkdocs serve`.

### Build is slow

Disable the search plugin in `mkdocs.yml` while iterating — it indexes every page on every save. Re-enable before committing.

## Reference

- [MkDocs docs](https://www.mkdocs.org/)
- [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/)
- [pymdown-extensions](https://facelessuser.github.io/pymdown-extensions/)
