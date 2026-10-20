#!/usr/bin/env python3
"""Adds a Seedy release to a Jellyfin plugin repository manifest (manifest.json), keeping the older releases.

    python3 scripts/update-manifest.py --manifest pages/manifest.json --zip artifacts/seedy_0.2.0.0.zip \
        --version 0.2.0.0 --source-url https://…/seedy_0.2.0.0.zip [--changelog-file notes.md] [--image-url …]

Jellyfin reads this file when someone adds the repository under Dashboard › Plugins › Repositories. A release that is
already listed with the same version is replaced. Standard library only.
"""
import argparse
import datetime
import hashlib
import json
import os

PLUGIN = {
    'guid': 'b547483f-57e9-439e-8a2a-561828a524a6',
    'name': 'Seedy',
    'description': (
        "A new web app for Jellyfin: a home screen that learns from each profile, Seerr requests, Live TV with a guide, "
        "a full admin dashboard and personal settings, in English and French. Replaces Jellyfin's web app at the server "
        "address (which moves to /old), or opens alongside it at /seedy."
    ),
    'overview': 'A new web app for Jellyfin, with Seerr requests and Live TV',
    'category': 'General',
}


def version_key(v):
    return tuple(int(p) if p.isdigit() else 0 for p in v.split('.'))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--manifest', required=True)
    ap.add_argument('--zip', required=True)
    ap.add_argument('--version', required=True, help='four-part plugin version, e.g. 0.2.0.0')
    ap.add_argument('--source-url', required=True, help='where Jellyfin downloads the zip')
    ap.add_argument('--target-abi', default='12.0.0.0', help='oldest Jellyfin the release runs on')
    ap.add_argument('--changelog', default='')
    ap.add_argument('--changelog-file')
    ap.add_argument('--owner', default='Seedy')
    ap.add_argument('--image-url', default='')
    a = ap.parse_args()

    changelog = a.changelog
    if a.changelog_file and os.path.exists(a.changelog_file):
        changelog = open(a.changelog_file, encoding='utf-8').read().strip()

    with open(a.zip, 'rb') as f:
        checksum = hashlib.md5(f.read()).hexdigest()

    manifest = []
    if os.path.exists(a.manifest):
        with open(a.manifest, encoding='utf-8') as f:
            manifest = json.load(f)

    entry = next((p for p in manifest if p.get('guid') == PLUGIN['guid']), None)
    if entry is None:
        entry = {'versions': []}
        manifest.append(entry)
    versions = [v for v in entry.get('versions', []) if v.get('version') != a.version]
    entry.update(PLUGIN, owner=a.owner)
    if a.image_url:
        entry['imageUrl'] = a.image_url
    versions.append({
        'version': a.version,
        'changelog': changelog or f'Seedy {a.version}',
        'targetAbi': a.target_abi,
        'sourceUrl': a.source_url,
        'checksum': checksum,
        'timestamp': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    })
    entry['versions'] = sorted(versions, key=lambda v: version_key(v['version']), reverse=True)

    os.makedirs(os.path.dirname(os.path.abspath(a.manifest)), exist_ok=True)
    with open(a.manifest, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write('\n')
    print(f"{a.manifest}: Seedy {a.version} ({checksum}), {len(entry['versions'])} release(s) listed")


if __name__ == '__main__':
    main()
