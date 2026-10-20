# Contributing

Issues and pull requests are welcome. For anything larger than a fix, open an issue first so we can agree on the shape.

## Getting started

See **Develop** in the [README](README.md): a local Jellyfin 12 and Seerr in Docker (`make dev-up`), `make dev-install`,
and the Vite dev server at `http://127.0.0.1:5173/seedy/` (`make web`). Before sending a change:

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
- **CSS classes** carry their area's prefix: `nb-` navbar, `me-` personal settings, `admin-` the admin shell, `srv-`
  server dashboard (`adv-` on its Advanced pages), `lt-` Live TV, `pl-` player.
- **Style:** match the surrounding code; keep components small and the design tokens in `web/src/ui/tokens.css`.

## For maintainers: releasing

Releases are built by GitHub Actions ([`.github/workflows/release.yml`](.github/workflows/release.yml)):

1. Add the release notes under a `## <version>` heading in [CHANGELOG.md](CHANGELOG.md).
2. Push a tag: `git tag v0.2.0 && git push origin v0.2.0`.

The workflow builds the plugin with that version, attaches `seedy_<version>.zip` to a GitHub Release, and adds the
release to `manifest.json` on the `gh-pages` branch, keeping the older ones.

Once, after the first release: in the repository's **Settings › Pages**, choose **Deploy from a branch**, branch
`gh-pages`, folder `/`. The repository address is then `https://arkmind.github.io/seedy/manifest.json`.

## License

By contributing, you agree that your contributions are licensed under the [GPL-3.0-or-later](LICENSE).
