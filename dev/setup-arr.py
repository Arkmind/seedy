#!/usr/bin/env python3
"""Connects the test Sonarr, Sonarr 4K and Radarr to the test Seerr (run ./setup-seerr.py first).
Gives each a root folder, then adds it to Seerr as a server with its first quality profile. Safe to run again:
servers Seerr already has (same name) are updated, not added twice. Prints nothing secret."""
import json, re, time, urllib.request, urllib.error
from pathlib import Path

HERE = Path(__file__).resolve().parent
SEERR = 'http://localhost:5055/api/v1'

# name, Seerr kind, URL from here, host and port from Seerr's container, config folder, root folder, 4K
APPS = [
    ('Sonarr', 'sonarr', 'http://localhost:8989', 'sonarr', 8989, 'sonarr', '/data/tv', False),
    ('Sonarr 4K', 'sonarr', 'http://localhost:8990', 'sonarr-4k', 8989, 'sonarr-4k', '/data/tv-4k', True),
    ('Radarr', 'radarr', 'http://localhost:7878', 'radarr', 7878, 'radarr', '/data/movies', False),
]


def call(method, url, key, body=None):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={'Content-Type': 'application/json', 'Accept': 'application/json', 'X-Api-Key': key})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            t = r.read()
            return r.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


def api_key(folder):
    """The app writes its key to config.xml on first start."""
    cfg = HERE / 'data' / folder / 'config.xml'
    for _ in range(60):
        if cfg.exists():
            m = re.search(r'<ApiKey>(\w+)</ApiKey>', cfg.read_text())
            if m:
                return m.group(1)
        time.sleep(2)
    raise SystemExit(f'No API key in {cfg}: is the container running (make dev-up)?')


def wait_up(url, key, ver):
    for _ in range(60):
        try:
            if call('GET', f'{url}/api/{ver}/system/status', key)[0] == 200:
                return
        except (urllib.error.URLError, ConnectionError):
            pass
        time.sleep(2)
    raise SystemExit(f'{url} did not answer')


seerr_key = (HERE / 'data' / 'seerr-apikey').read_text().strip()
for name, kind, url, host, port, folder, root, is4k in APPS:
    key = api_key(folder)
    wait_up(url, key, 'v3')
    s, roots = call('GET', f'{url}/api/v3/rootfolder', key)
    if not any(r['path'] == root for r in roots):
        print(name, 'root folder', call('POST', f'{url}/api/v3/rootfolder', key, {'path': root})[0])
    s, profiles = call('GET', f'{url}/api/v3/qualityprofile', key)
    want = 'Ultra-HD' if is4k else 'HD-1080p'
    profile = next((p for p in profiles if p['name'] == want), profiles[0])
    body = {
        'name': name, 'hostname': host, 'port': port, 'apiKey': key, 'useSsl': False, 'baseUrl': '',
        'activeProfileId': profile['id'], 'activeProfileName': profile['name'], 'activeDirectory': root,
        'is4k': is4k, 'isDefault': True, 'externalUrl': url, 'syncEnabled': False, 'preventSearch': True, 'tags': [],
    }
    if kind == 'sonarr':
        body.update({'activeAnimeProfileId': profile['id'], 'activeAnimeProfileName': profile['name'],
                     'activeAnimeDirectory': root, 'animeTags': [], 'enableSeasonFolders': True, 'seriesType': 'standard'})
    else:
        body.update({'minimumAvailability': 'released'})
    s, existing = call('GET', f'{SEERR}/settings/{kind}', seerr_key)
    same = next((e for e in existing if e['name'] == name), None) if isinstance(existing, list) else None
    if same:
        print(name, 'updated in Seerr', call('PUT', f'{SEERR}/settings/{kind}/{same["id"]}', seerr_key, body)[0])
    else:
        print(name, 'added to Seerr', call('POST', f'{SEERR}/settings/{kind}', seerr_key, body)[0])
