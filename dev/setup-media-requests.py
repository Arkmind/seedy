#!/usr/bin/env python3
"""Sets up the test Lidarr, Shelfarr and Librarr for music, book and comic requests, and saves what Seedy needs to talk to
them in dev/data (git-ignored): lidarr-apikey, shelfarr-token, librarr-apikey. Safe to run again: the root folder, admin and
token are only made when missing or no longer working. Prints nothing secret."""
import json, re, subprocess, time, urllib.request, urllib.error
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE / 'data'
LIDARR, SHELFARR, LIBRARR = 'http://localhost:8686', 'http://localhost:5056', 'http://localhost:5050'
# Shelfarr's admin, for its own web UI (its passwords need 12 characters, upper and lower case and a digit).
SHELFARR_USER, SHELFARR_PASSWORD = 'alex', 'SeedyDev2026'
SCOPES = ['search:read', 'requests:read', 'requests:write', 'requests:admin', 'users:write']


def call(method, url, headers, body=None):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={'Content-Type': 'application/json', 'Accept': 'application/json', **headers})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read()
            return r.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        return 0, str(e)


def wait(name, url, headers):
    for _ in range(90):
        s, _ = call('GET', url, headers)
        if 200 <= s < 500 and s != 502:
            return s
        time.sleep(2)
    raise SystemExit(f'{name} did not answer at {url}: is it running (make dev-up)?')


def save(name, value):
    (DATA / name).write_text(value + '\n')
    print(f'  saved to dev/data/{name}')


# Lidarr: a root folder for the artists Seerr-style requests add, with its default profiles.
cfg = DATA / 'lidarr' / 'config.xml'
for _ in range(60):
    m = cfg.exists() and re.search(r'<ApiKey>(\w+)</ApiKey>', cfg.read_text())
    if m:
        break
    time.sleep(2)
else:
    raise SystemExit(f'No API key in {cfg}: is Lidarr running (make dev-up)?')
key = {'X-Api-Key': m.group(1)}
wait('Lidarr', f'{LIDARR}/api/v1/system/status', key)
s, status = call('GET', f'{LIDARR}/api/v1/system/status', key)
print('Lidarr', s, status.get('version') if isinstance(status, dict) else status)
s, quality = call('GET', f'{LIDARR}/api/v1/qualityprofile', key)
s, metadata = call('GET', f'{LIDARR}/api/v1/metadataprofile', key)
q = next((p for p in quality if p['name'] == 'Any'), quality[0])
md = next((p for p in metadata if p['name'] == 'Standard'), metadata[0])
s, roots = call('GET', f'{LIDARR}/api/v1/rootfolder', key)
if not any(r['path'].rstrip('/') == '/data/music' for r in roots):
    s, _ = call('POST', f'{LIDARR}/api/v1/rootfolder', key, {
        'name': 'Music', 'path': '/data/music', 'defaultQualityProfileId': q['id'], 'defaultMetadataProfileId': md['id'],
        'defaultMonitorOption': 'all', 'defaultNewItemMonitorOption': 'all', 'defaultTags': []})
    print('  root folder /data/music', s)
print('  quality profiles:', ', '.join(p['name'] for p in quality), f'(root folder uses {q["name"]})')
print('  metadata profiles:', ', '.join(p['name'] for p in metadata), f'(root folder uses {md["name"]})')
save('lidarr-apikey', m.group(1))

# Shelfarr: an admin and a user API token for Seedy, made in its Rails console (its API has no sign-up). The first-run
# wizard only concerns its web UI; it's marked done so the UI opens on the dashboard.
wait('Shelfarr', f'{SHELFARR}/up', {})
token_file = DATA / 'shelfarr-token'
token = token_file.read_text().strip() if token_file.exists() else ''
if not token or call('GET', f'{SHELFARR}/api/v1/requests', {'Authorization': f'Bearer {token}'})[0] != 200:
    ruby = f'''
u = User.active.find_by(username: "{SHELFARR_USER}") || User.create!(username: "{SHELFARR_USER}", name: "Alex", password: "{SHELFARR_PASSWORD}", role: :admin)
u.update!(role: :admin) unless u.admin?
SettingsService.set(:setup_wizard_completed, true)
u.api_tokens.where(name: "Seedy", revoked_at: nil).update_all(revoked_at: Time.current)
_, raw = APIToken.issue!(name: "Seedy", user: u, scopes: {json.dumps(SCOPES).replace('"', "'")})
puts "TOKEN=#{{raw}}"
'''
    # The keys the container generated on first start, which only its server process has in its environment.
    shell = 'export SECRET_KEY_BASE="$(cat storage/.secret_key_base)"; . storage/.encryption_keys; exec bin/rails runner "$0"'
    out = subprocess.run(['docker', 'exec', '-w', '/rails', 'seedy-shelfarr', 'sh', '-c', shell, ruby], capture_output=True, text=True)
    m = re.search(r'^TOKEN=(shf_\S+)$', out.stdout, re.M)
    if not m:
        raise SystemExit('Shelfarr token: rails runner failed\n' + out.stderr[-800:])
    token = m.group(1)
    print('Shelfarr admin', SHELFARR_USER, 'and a new API token')
bearer = {'Authorization': f'Bearer {token}'}
s, reqs = call('GET', f'{SHELFARR}/api/v1/requests', bearer)
print('Shelfarr requests', s, len(reqs['requests']) if isinstance(reqs, dict) else reqs)
s, found = call('GET', f'{SHELFARR}/api/v1/search?q=dune&limit=3', bearer)
print('Shelfarr search "dune"', s, f'{len(found["results"])} results' if isinstance(found, dict) else found)
save('shelfarr-token', token)

# Librarr: the key is set in docker-compose.yml (API_KEY); check it opens the API.
out = subprocess.run(['docker', 'inspect', 'seedy-librarr', '--format', '{{json .Config.Env}}'], capture_output=True, text=True)
env = dict(e.split('=', 1) for e in json.loads(out.stdout or '[]'))
if not env.get('API_KEY'):
    raise SystemExit('Librarr has no API_KEY: is it running (make dev-up)?')
lkey = {'X-Api-Key': env['API_KEY']}
wait('Librarr', f'{LIBRARR}/api/health', lkey)
s, health = call('GET', f'{LIBRARR}/api/health', lkey)
print('Librarr', s, health.get('version') if isinstance(health, dict) else health)
s, reqs = call('GET', f'{LIBRARR}/api/requests', lkey)
print('Librarr requests', s, reqs.get('total') if isinstance(reqs, dict) else reqs)
if call('GET', f'{LIBRARR}/api/requests', {})[0] != 401:
    print('  warning: Librarr answered without the key')
save('librarr-apikey', env['API_KEY'])
