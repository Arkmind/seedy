#!/usr/bin/env python3
"""Sets up the test Seerr against the test Jellyfin and prints nothing secret.
Alex becomes the Seerr admin; Sam is imported; Léa and Kids stay without accounts (as on the Requests board)."""
import json, urllib.request, http.cookiejar, sys
S = 'http://localhost:5055/api/v1'
jar = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def call(method, path, body=None):
    req = urllib.request.Request(S + path, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={'Content-Type': 'application/json', 'Accept': 'application/json'})
    try:
        with op.open(req) as r:
            t = r.read(); return r.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
print('auth', call('POST', '/auth/jellyfin', {'username': 'Alex', 'password': 'seedy', 'hostname': 'jellyfin', 'port': 8096, 'useSsl': False, 'urlBase': '', 'email': 'alex@example.com', 'serverType': 2})[0])
# Seerr 3.x: sync the library list, then enable each library.
s, libs = call('POST', '/settings/jellyfin/library/sync')
for l in libs if isinstance(libs, list) else []:
    print('enable', l['name'], call('PUT', '/settings/jellyfin/library/' + l['id'], {'enabled': True})[0])
s, users = call('GET', '/settings/jellyfin/users')
print('jellyfin users', s, [u.get('username') for u in users] if isinstance(users, list) else users)
if isinstance(users, list):
    sam = [u['id'] for u in users if u.get('username') == 'Sam']
    print('import', call('POST', '/user/import-from-jellyfin', {'jellyfinUserIds': sam})[0])
print('initialize', call('POST', '/settings/initialize')[0])
print('scan', call('POST', '/settings/jobs/jellyfin-full-scan/run')[0])
s, main = call('GET', '/settings/main')
key = main.get('apiKey') if isinstance(main, dict) else None
if key:
    open('data/seerr-apikey', 'w').write(key)
    print('api key saved to dev/data/seerr-apikey')
