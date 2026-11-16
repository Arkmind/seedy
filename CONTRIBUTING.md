# Contributing

Issues and pull requests are welcome. For anything larger than a fix, open an issue first so we can agree on the shape.

## Getting started

See **Develop** in the [README](README.md): a local Jellyfin 12 and Seerr in Docker (`make dev-up`), `make dev-install`,
and the Vite dev server at `http://127.0.0.1:5173/seedy/` (`make web`). The test library only holds openly licensed or
generated media (`make dev-media`, then `make dev-libraries` for the Music and Books libraries), so screenshots never
show anyone else's work; keep it that way. Before sending a change:

```bash
make typecheck   # the web app
make build       # full build: web app, plugin, package
```

## Conventions

- **Text is translated.** Write UI text in English and wrap it: `t('Text')`, `tn(n, '{n} movie', '{n} movies')`, or
  `tx('Text with {part}', { part: <Element /> })` from `web/src/lib/i18n.ts`. The English text is the key; add the
  French to a file in `web/src/i18n/fr/`. Never call `t()` at module load (translate where text is rendered).
  New languages: a folder `web/src/i18n/<code>/` plus an entry in `LANGS`.
- **Dates and numbers** go through `web/src/lib/format.ts` and `formatNumber()`, so they follow the language.
- **Jellyfin 12:** send `Accept: application/json` (PascalCase JSON); query-string auth is `ApiKey=`, once per URL.
- **Routes:** Seedy serves its pages at the server root next to Jellyfin's API. A page with a new first path segment
  must be added to `Pages` in `src/Jellyfin.Plugin.Seedy/Services/RootRedirect.cs`, and a Seedy API path must never
  equal a page path (routes are case-insensitive).
- **Where things go:** a screen lives in its area's folder under `web/src/screens/` with its stylesheet; pieces several
  pages of an area share go in that folder's `parts.tsx`, and pieces the whole app shares in `web/src/ui/`. Screens and
  single components are `PascalCase.tsx`; other modules are `kebab-case`.
- **CSS classes** carry their area's prefix: `nb-` navbar, `me-` personal settings, `admin-` the admin shell (`st-`
  Statistics, `amr-` music and book requests), `srv-` server dashboard (`adv-` on its Advanced pages), `lt-` Live TV,
  `pl-` player, `ap-` audio player, `reqs-` Requests (`rq-` the season picker), `mu-` Music, `bk-` Books (`bkd-` a
  book's page, `rd-` and `rs-` the reader and its settings), `ph-` Photos.
- **Style:** match the surrounding code; keep components small and the design tokens in `web/src/ui/tokens.css`.

## For maintainers: releasing

Releases are built by GitHub Actions ([`.github/workflows/release.yml`](.github/workflows/release.yml)):

1. On GitHub, **Releases › Draft a new release**: a new tag such as `v0.2.0` on the default branch, a title, and the
   release notes.
2. **Publish release.**

The workflow builds the plugin with that version, attaches `seedy_<version>.zip` to the release, and adds the version to
`manifest.json` on the `gh-pages` branch, keeping the older ones. The release notes become the changelog Jellyfin shows
in its plugin catalogue.

Once, after the first release: in the repository's **Settings › Pages**, choose **Deploy from a branch**, branch
`gh-pages`, folder `/`. The repository address is then `https://arkmind.github.io/seedy/manifest.json`.

### Release notes

The Android app reads the release notes: its update screen lists the "What's Changed" items, each with an icon, and
Google Play's "What's new" is made from the same items. Write them like this:

```markdown
# What's Changed
- <!-- icon:play_button --> Your own “Still watching?” rules
  2–4 episodes, 90 min–3 h, or 1–3 movies
  <!-- fr: Vos propres règles « Toujours là ? » | 2 à 4 épisodes, 90 min à 3 h, ou 1 à 3 films -->
- <!-- icon:mobile --> See where you’re signed in
  Sign out a phone or TV you no longer use

**Full changelog**: https://github.com/Arkmind/seedy/compare/v0.4.6...v0.4.7
```

- One item per change worth telling people about, three or four in all: a title of up to 34 characters (one line on a
  phone), then an indented line saying what it means for them.
- `<!-- icon:… -->` picks the item's icon. The keys are in
  [`ReleaseIcons.kt`](android/ui/src/main/kotlin/io/github/arkmind/seedy/ui/icons/ReleaseIcons.kt) (`play_button`,
  `mobile`, `tv`, `sparkles`, `music`, `book`, …); an unknown key shows sparkles.
- `<!-- fr: title | line -->` is optional: the French the app shows instead in French.
- The comments don't show on GitHub, and they're removed from the changelog in Jellyfin's plugin catalogue. Other
  sections after the list (fixes, upgrade notes) only show on GitHub.

Check a draft before publishing: `python3 scripts/release-notes.py check < notes.md` prints the items as the app shows
them, Google Play's text (500 characters at most), and anything wrong.

## License

By contributing, you agree that your contributions are licensed under the [GPL-3.0-or-later](LICENSE).
