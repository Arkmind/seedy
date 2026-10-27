#!/usr/bin/env python3
"""Connects Seedy to the test Lidarr, Shelfarr and Librarr (run ./setup-media-requests.py first, which saves their keys
in dev/data), and seeds music and book requests in every state the Requests boards show: waiting for approval (Sam,
Léa), approved, searching, downloading, needing a look, and on the server. Seeded requests are marked as demo data, so
Seedy doesn't send them to the apps; approving one just marks it approved. Safe to run again: the demo requests are
replaced. Prints nothing secret.

  python3 setup-seedy-requests.py            configure and seed
  python3 setup-seedy-requests.py --clear    remove the demo requests
"""
import json, sqlite3, sys, urllib.error, urllib.parse, urllib.request, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE / 'data'
JELLYFIN = 'http://localhost:8096'
ADMIN, PASSWORD = 'Alex', 'seedy'
DB = DATA / 'config' / 'data' / 'seedy' / 'seedy.db'
LIDARR = 'http://localhost:8686'
# Addresses as the Jellyfin container reaches the apps on the compose network.
APPS = {'lidarr': 'http://lidarr:8686', 'shelfarr': 'http://shelfarr:80', 'librarr': 'http://librarr:5050'}
SECRETS = {'lidarr': 'lidarr-apikey', 'shelfarr': 'shelfarr-token', 'librarr': 'librarr-apikey'}


def call(method, url, headers=None, body=None):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={'Content-Type': 'application/json', 'Accept': 'application/json', **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read()
            return r.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        return 0, str(e)


def secret(name):
    f = DATA / SECRETS[name]
    if not f.exists():
        raise SystemExit(f'No dev/data/{SECRETS[name]}: run ./setup-media-requests.py first.')
    return f.read_text().strip()


def sign_in():
    s, auth = call('POST', f'{JELLYFIN}/Users/AuthenticateByName',
                   {'Authorization': 'MediaBrowser Client="Seedy dev", Device="setup", DeviceId="seedy-setup-requests", Version="1"'},
                   {'Username': ADMIN, 'Pw': PASSWORD})
    if s != 200:
        raise SystemExit(f'Jellyfin sign-in failed ({s}): is the dev stack up with the Seedy plugin installed?')
    return {'Authorization': f'MediaBrowser Token={auth["AccessToken"]}'}


def lidarr_profiles():
    """Board names for the dev Lidarr's profiles, so the request window reads "Lossless (FLAC)" and the fallback
    "Accept MP3 320", and returns the ids Seedy uses."""
    key = {'X-Api-Key': secret('lidarr')}
    names = {'Lossless': 'Lossless (FLAC)', 'Standard': 'Standard (MP3 320)'}
    s, quality = call('GET', f'{LIDARR}/api/v1/qualityprofile', key)
    if s != 200:
        raise SystemExit(f'Lidarr quality profiles: {s} {quality}')
    for p in quality:
        if p['name'] in names:
            call('PUT', f'{LIDARR}/api/v1/qualityprofile/{p["id"]}', key, {**p, 'name': names[p['name']]})
            p['name'] = names[p['name']]
    s, metadata = call('GET', f'{LIDARR}/api/v1/metadataprofile', key)
    for p in metadata if s == 200 else []:
        if p['name'] == 'Standard':
            call('PUT', f'{LIDARR}/api/v1/metadataprofile/{p["id"]}', key, {**p, 'name': 'Albums and EPs'})
            p['name'] = 'Albums and EPs'
    s, roots = call('GET', f'{LIDARR}/api/v1/rootfolder', key)
    q = next((p for p in quality if p['name'].startswith('Lossless')), quality[0])
    m = next((p for p in metadata if p['name'] == 'Albums and EPs'), metadata[0]) if metadata else {'id': 0}
    root = roots[0]['path'] if s == 200 and roots else ''
    print(f'Lidarr: quality "{q["name"]}", release types "{m.get("name", "?")}", root folder {root or "?"}')
    return q['id'], m['id'], root


def configure(h):
    quality, metadata, root = lidarr_profiles()
    s, c = call('GET', f'{JELLYFIN}/Seedy/Admin/Config', h)
    if s != 200:
        raise SystemExit(f'Seedy admin config: {s} {c}')
    media = c.get('media') or {}
    media['lidarr'] = {**media.get('lidarr', {}), 'url': APPS['lidarr'], 'qualityProfileId': quality, 'metadataProfileId': metadata,
                       'rootFolder': root, 'wholeArtist': 'all', 'searchOnApprove': True}
    media['shelfarr'] = {**media.get('shelfarr', {}), 'enabled': True, 'url': APPS['shelfarr']}
    media['librarr'] = {**media.get('librarr', {}), 'enabled': True, 'url': APPS['librarr']}
    media['readarr'] = {**media.get('readarr', {}), 'enabled': False}
    media['routes'] = {'ebooks': 'shelfarr', 'audiobooks': 'shelfarr', 'comics': 'librarr'}
    media.setdefault('scanOnReady', True)
    media.setdefault('notifyOnReady', True)
    media.setdefault('showRequesters', False)
    c['media'] = media
    s, _ = call('POST', f'{JELLYFIN}/Seedy/Admin/Config', h, c)
    print('Seedy: apps and routes', s)
    for service in ('lidarr', 'shelfarr', 'librarr'):
        s, _ = call('POST', f'{JELLYFIN}/Seedy/Admin/MediaRequests/Key', h, {'service': service, 'apiKey': secret(service)})
        s, r = call('POST', f'{JELLYFIN}/Seedy/Admin/MediaRequests/Test/{service}', h, {})
        print(f'  {service}: key saved, test {"ok" if isinstance(r, dict) and r.get("ok") else r}')


def users(h):
    s, us = call('GET', f'{JELLYFIN}/Users', h)
    return {u['Name']: u['Id'].replace('-', '').lower() for u in us}


def item(h, kind, name):
    s, r = call('GET', f'{JELLYFIN}/Items?IncludeItemTypes={kind}&Recursive=true&SearchTerm={urllib.parse.quote(name)}&Limit=5', h)
    for i in (r or {}).get('Items', []) if s == 200 else []:
        if i['Name'].lower() == name.lower():
            return i['Id']
    return None


def ms(days_ago, hour=18):
    d = datetime.now(timezone.utc).replace(hour=hour, minute=12, second=0, microsecond=0) - timedelta(days=days_ago)
    return int(d.timestamp() * 1000)


# The boards' requests: who, kind, app, mode, title (the artist for music), subtitle, details, state.
# Days are counted back from today, so the dates read as on the boards when today is Tue 27 Oct 2026.
def demo(h, ids):
    mbid = lambda: str(uuid.uuid4())
    out = []

    def add(who, kind, service, mode, title, subtitle=None, units=1, data=None, status='pending', asked=0, decided=None,
            note=None, progress=None, attention=False, item_id=None, formats=None):
        data = {'Frozen': True, **(data or {})}
        parts = [{'Format': 'music', 'Service': 'lidarr', 'Status': status, 'Progress': progress}] if kind == 'music' else \
                [{'Format': f, 'Service': service, 'Status': status, 'Progress': progress} for f in (formats or ['ebook'])]
        if kind == 'book':
            data.setdefault('Formats', formats or ['ebook'])
        out.append({
            'id': uuid.uuid4().hex, 'user_id': ids[who], 'user_name': who, 'kind': kind, 'service': service, 'mode': mode,
            'foreign_id': data.pop('Foreign', None) or (mbid() if kind == 'music' else 'openlibrary:demo-' + title.lower().replace(' ', '-')),
            'title': title, 'subtitle': subtitle, 'cover_url': None, 'units': units, 'data': json.dumps(data), 'parts': json.dumps(parts),
            'status': status, 'note': json.dumps(note) if note else None, 'progress': progress, 'attention': 1 if attention else 0,
            'item_id': item_id, 'created_at': ms(asked, 17), 'updated_at': ms(decided if decided is not None else asked, 19),
            'decided_at': ms(decided, 18) if decided is not None else None, 'decided_by': 'Alex' if decided is not None else None,
            'sent_at': ms(decided, 18) if decided is not None and status != 'pending' else None,
            'searched_at': ms(decided, 18) if status in ('searching', 'downloading') else None,
            'ready_at': ms(asked - 1, 20) if status == 'ready' else None})

    album = lambda title, year: {'Mbid': mbid(), 'Title': title, 'Type': 'album', 'Year': year}
    # Music: Alex's own, then Sam's and Léa's waiting for approval.
    add('Alex', 'music', 'lidarr', 'artist', 'Mira Sol', units=5, data={'KeepNew': True}, status='downloading', asked=8, decided=8,
        note={'code': 'albums', 'n': 3, 'total': 5, 'name': 'Glasswork', 'fraction': 0.4}, progress=0.6)
    add('Alex', 'music', 'lidarr', 'albums', 'Lumen Fields', data={'Albums': [album('Blue Hour (Deluxe)', 2025)]}, status='ready', asked=10, decided=10,
        item_id=item(h, 'MusicAlbum', 'Blue Hour'))
    add('Alex', 'music', 'lidarr', 'albums', 'Kestrel Choir', data={'Albums': [album('Small Hours', 2026)]}, status='searching', asked=12, decided=12,
        note={'code': 'noCopy', 'name': 'lossless', 'days': 6}, attention=True)
    add('Sam', 'music', 'lidarr', 'albums', 'Harbor Lights', data={'Albums': [album('Low Water', 2026)]}, asked=0)
    add('Léa', 'music', 'lidarr', 'future', 'Oren Moss', units=0, data={'KeepNew': True}, asked=1)
    # Books: Alex's own, then Sam's waiting, one Shelfarr couldn't find and one sent but not searched yet.
    add('Alex', 'book', 'shelfarr', 'book', 'Lantern Season', 'Mara Linde', formats=['audiobook'], status='searching', asked=9, decided=9,
        note={'code': 'found', 'n': 2, 'name': 'Shelfarr'})
    add('Alex', 'book', 'shelfarr', 'book', 'Salt & Signal', 'Jonah Reyes', formats=['ebook'], status='ready', asked=11, decided=11,
        item_id=item(h, 'Book', 'Salt & Signal'))
    add('Alex', 'book', 'librarr', 'book', 'Kestrel No. 6', 'Hale & Ito', formats=['comic'], data={'Content': 'comic', 'Series': 'Kestrel', 'SeriesIndex': 6}, asked=0)
    add('Sam', 'book', 'shelfarr', 'book', 'The Salt Road', 'Jonah Reyes', units=2, formats=['ebook', 'audiobook'],
        data={'Series': 'Salt & Signal', 'SeriesIndex': 2}, asked=0)
    add('Sam', 'book', 'shelfarr', 'book', 'North of Nowhere', 'Dana Holm', formats=['audiobook'], status='failed', asked=6, decided=5,
        note={'code': 'service', 'name': 'Shelfarr', 'text': 'No audiobook release matched on any indexer'}, attention=True)
    add('Léa', 'book', 'shelfarr', 'book', 'Field Notes on Leaving', 'Ada Sørum', formats=['ebook'], status='approved', asked=2, decided=2)
    return out


def seed(h, clear_only):
    if not DB.exists():
        raise SystemExit(f'No {DB}: install the Seedy plugin into the dev stack first (make dev-install).')
    db = sqlite3.connect(DB, timeout=10)
    try:
        db.execute("DELETE FROM media_requests WHERE json_extract(data, '$.Frozen') = 1")
        if clear_only:
            db.commit()
            print('Removed the demo requests')
            return
        rows = demo(h, users(h))
        cols = list(rows[0].keys())
        db.executemany(f'INSERT INTO media_requests ({", ".join(cols)}) VALUES ({", ".join("?" for _ in cols)})', [[r[c] for c in cols] for r in rows])
        db.commit()
        print(f'Seeded {len(rows)} demo requests:', ', '.join(f'{r["title"]} ({r["status"]})' for r in rows))
    finally:
        db.close()


if __name__ == '__main__':
    headers = sign_in()
    clear = '--clear' in sys.argv
    if not clear:
        configure(headers)
    seed(headers, clear)
