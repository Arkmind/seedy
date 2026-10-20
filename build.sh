#!/usr/bin/env bash
# Builds the Seedy plugin: web app → embedded resources → DLL → zip, plus a plugin repository manifest.
#
#   ./build.sh                 build and package into ./artifacts
#   ./build.sh --install-dev   also install into the local test server (dev/docker-compose.yml) and restart it
#
# Environment (all optional; the release workflow sets them):
#   VERSION     four-part plugin version (default: the csproj's)
#   REPO_URL    folder the zip is downloaded from, used in artifacts/manifest.json
#   OWNER       owner shown in Jellyfin's plugin catalogue (default: Seedy)
#   CHANGELOG   text shown as this release's changelog (default: "Seedy <version>")
# Uses a local .NET 10 SDK when there is one, otherwise builds in the mcr.microsoft.com/dotnet/sdk:10.0 container.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CSPROJ_VERSION="$(sed -n 's:.*<Version>\(.*\)</Version>.*:\1:p' "$ROOT/src/Jellyfin.Plugin.Seedy/Jellyfin.Plugin.Seedy.csproj" | head -1)"
VERSION="${VERSION:-$CSPROJ_VERSION}"
TARGET_ABI="12.0.0.0"
REPO_URL="${REPO_URL:-https://REPLACE-ME/seedy}"
OWNER="${OWNER:-Seedy}"
CHANGELOG="${CHANGELOG:-Seedy $VERSION}"
OUT="$ROOT/artifacts"
PKG="seedy_${VERSION}.zip"

echo "› Seedy ${VERSION}"

echo "› Web app"
( cd "$ROOT/web" && (command -v bun >/dev/null && bun install --frozen-lockfile >/dev/null || npm ci >/dev/null) && \
  if [ "${SKIP_TYPECHECK:-0}" = "1" ]; then bunx vite build --logLevel warn; else bun run build -- --logLevel warn; fi )

echo "› Plugin"
PUBLISH=(dotnet publish -c Release -nologo -v q -p:TreatWarningsAsErrors=false
  -p:Version="$VERSION" -p:AssemblyVersion="$VERSION" -p:FileVersion="$VERSION")
if command -v dotnet >/dev/null && dotnet --list-sdks 2>/dev/null | grep -q '^10\.'; then
  ( cd "$ROOT/src/Jellyfin.Plugin.Seedy" && "${PUBLISH[@]}" -o "$OUT/publish" >/dev/null )
else
  docker run --rm -v "$ROOT":/work -v seedy-nuget:/root/.nuget/packages -w /work/src/Jellyfin.Plugin.Seedy \
    mcr.microsoft.com/dotnet/sdk:10.0 "${PUBLISH[@]}" -o /work/artifacts/publish >/dev/null
fi

rm -rf "$OUT/package" && mkdir -p "$OUT/package"
cp "$OUT/publish/Jellyfin.Plugin.Seedy.dll" "$OUT/package/"
TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
VERSION="$VERSION" OWNER="$OWNER" CHANGELOG="$CHANGELOG" TARGET_ABI="$TARGET_ABI" TIMESTAMP="$TIMESTAMP" \
python3 - "$OUT/package/meta.json" <<'PY'
import json, os, sys
e = os.environ
json.dump({
    "category": "General",
    "changelog": e["CHANGELOG"],
    "description": "A new web app for Jellyfin: a home screen that learns from each profile, Seerr requests, Live TV with a guide, a full admin dashboard and personal settings, in English and French.",
    "guid": "b547483f-57e9-439e-8a2a-561828a524a6",
    "name": "Seedy",
    "overview": "A new web app for Jellyfin, with Seerr requests and Live TV",
    "owner": e["OWNER"],
    "targetAbi": e["TARGET_ABI"],
    "timestamp": e["TIMESTAMP"],
    "version": e["VERSION"],
    "status": "Active",
    "autoUpdate": True,
    "assemblies": ["Jellyfin.Plugin.Seedy.dll"],
}, open(sys.argv[1], "w"), indent=2)
PY
( cd "$OUT/package" && rm -f "../$PKG" && zip -q -X "../$PKG" Jellyfin.Plugin.Seedy.dll meta.json )
rm -f "$OUT/manifest.json"
python3 "$ROOT/scripts/update-manifest.py" --manifest "$OUT/manifest.json" --zip "$OUT/$PKG" --version "$VERSION" \
  --source-url "${REPO_URL}/${PKG}" --target-abi "$TARGET_ABI" --owner "$OWNER" --changelog "$CHANGELOG" >/dev/null
echo "› Packaged artifacts/${PKG} (md5 $(md5sum "$OUT/$PKG" | cut -d' ' -f1)) and artifacts/manifest.json"

if [ "${1:-}" = "--install-dev" ]; then
  DEST="$ROOT/dev/data/config/plugins/Seedy_${VERSION}"
  echo "› Installing into ${DEST#$ROOT/}"
  rm -rf "$ROOT"/dev/data/config/plugins/Seedy_* && mkdir -p "$DEST"
  cp "$OUT/package/Jellyfin.Plugin.Seedy.dll" "$OUT/package/meta.json" "$DEST/"
  docker restart seedy-jellyfin >/dev/null
  for _ in $(seq 1 60); do
    curl -fs -o /dev/null http://localhost:8096/System/Info/Public && break
    sleep 2
  done
  echo "› Test server restarted"
fi
