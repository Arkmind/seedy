#!/usr/bin/env python3
"""Fills the test server's play log with about two months of made-up plays so the admin Statistics pages have something to
show: every profile, evenings busier than mornings, weekends busier than weekdays, shows, movies, songs, audiobooks, books
and Live TV, on a few named devices, some of them transcoded. Run it after installing a Seedy build that has Statistics
(the play log needs its newer columns). Safe to run again: it replaces the plays it added before and leaves real ones alone.
Prints nothing secret."""
import json, os, random, sqlite3, sys, urllib.parse, urllib.request, urllib.error, uuid
from datetime import datetime, timedelta, timezone

JF = 'http://localhost:8096'
CLIENT = 'MediaBrowser Client="Seedy dev", Device="seed-plays", DeviceId="seedy-dev-seed-plays", Version="1.0"'
DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'config', 'data', 'seedy', 'seedy.db')
DAYS = int(os.environ.get('DAYS', '60'))
TAG = 'seed-plays'
token = None


def call(method, path, body=None, query=None):
    url = JF + path + ('?' + urllib.parse.urlencode(query, doseq=True) if query else '')
    auth = CLIENT + (f', Token="{token}"' if token else '')
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={'Content-Type': 'application/json', 'Accept': 'application/json', 'Authorization': auth})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read()
            return r.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


s, auth = call('POST', '/Users/AuthenticateByName', {'Username': 'Alex', 'Pw': 'seedy'})
if s >= 300:
    raise SystemExit(f'sign in as Alex failed: HTTP {s}')
token, alex = auth['AccessToken'], auth['User']['Id']

if not os.path.exists(DB):
    raise SystemExit(f'no play log at {DB}: start the test server with Seedy installed first')
db = sqlite3.connect(DB, timeout=10)
columns = {r[1] for r in db.execute('PRAGMA table_info(plays)')}
if 'watched_ms' not in columns:
    raise SystemExit('the play log has no Statistics columns yet: install this Seedy build (make dev-install) and restart first')


def items(kind, **extra):
    q = {'IncludeItemTypes': kind, 'Recursive': 'true', 'Fields': 'ParentId,RunTimeTicks,SeriesId,SeriesName,AlbumId,Album,ProductionYear,MediaSources', 'Limit': 400, **extra}
    s, r = call('GET', f'/Users/{alex}/Items', query=q)
    return (r or {}).get('Items', []) if s < 300 else []


def library_of(item, roots):
    # The library is the top folder the item sits under; Jellyfin's ancestors call says which.
    s, anc = call('GET', f'/Items/{item["Id"]}/Ancestors', query={'userId': alex})
    ids = {a['Id'] for a in anc or []} if s < 300 else set()
    return next((r for r in roots if r in ids), None)


s, views = call('GET', f'/Users/{alex}/Views')
roots = [v['Id'] for v in (views or {}).get('Items', [])]
s, users = call('GET', '/Users')
users = [u for u in users or [] if not u.get('Policy', {}).get('IsDisabled')]
if not users:
    raise SystemExit('no profiles')

movies = [i for i in items('Movie') if 'HEVC Test' not in i.get('Name', '')]
episodes = items('Episode')
songs = items('Audio')
audiobooks = items('AudioBook')
books = items('Book')
s, ch = call('GET', '/LiveTv/Channels', query={'userId': alex, 'Limit': 20})
channels = (ch or {}).get('Items', []) if s < 300 else []
print(f'{len(users)} profiles, {len(movies)} movies, {len(episodes)} episodes, {len(songs)} songs, {len(audiobooks)} audiobook files, {len(books)} books, {len(channels)} channels')

lib_cache = {}
def lib(item):
    key = item.get('SeriesId') or item.get('AlbumId') or item.get('ParentId') or item['Id']
    if key not in lib_cache:
        lib_cache[key] = library_of(item, roots)
    return lib_cache[key]


# Each profile has a couple of devices and a taste; the last one barely watches (the boards' "Guest").
DEVICES = [
    ('Living room TV', 'Seedy for Android TV', 'DirectPlay', 0.85),
    ('Bedroom Fire TV', 'Jellyfin Android TV', 'Transcode', 0.5),
    ('iPhone', 'Swiftfin iOS', 'Transcode', 0.55),
    ('Chrome on Windows', 'Seedy', 'DirectPlay', 0.7),
    ('Firefox on Linux', 'Seedy', 'DirectStream', 0.6),
    ('Pixel 8', 'Seedy for Android', 'DirectPlay', 0.8),
    ('Apple TV', 'Swiftfin tvOS', 'DirectPlay', 0.9),
]
random.seed(42)
profiles = []
for n, u in enumerate(users):
    devs = random.sample(DEVICES, 2) if n else [DEVICES[0], DEVICES[3]]
    weight = 0 if n == len(users) - 1 and len(users) > 2 else [1.0, 0.7, 0.5, 0.35, 0.25][min(n, 4)]
    taste = random.choice([{'show': 5, 'movie': 3, 'music': 2, 'livetv': 1, 'book': 1}, {'show': 3, 'movie': 4, 'music': 3, 'book': 2}, {'show': 6, 'movie': 2, 'livetv': 2}])
    profiles.append((u, devs, weight, taste))

REASONS = ['VideoCodecNotSupported', 'VideoCodecNotSupported', 'VideoCodecNotSupported, AudioCodecNotSupported', 'ContainerBitrateExceedsLimit', 'SubtitleCodecNotSupported', 'AudioCodecNotSupported']
rows = []
now = datetime.now(timezone.utc)
ms = lambda d: int(d.timestamp() * 1000)


def add(user, item, kind, start, watched_s, dev, title_id, title_name, item_type, runtime_ticks, position_ticks, finished, library, method=None, reasons=None, bitrate=None, vcodec=None, scodec=None):
    stop = start + timedelta(seconds=watched_s + random.randint(0, 120))
    rows.append((uuid.uuid4().hex, user['Id'].replace('-', ''), item['Id'], title_id, item_type, title_name, item.get('Name', ''), ms(start), ms(stop),
                 position_ticks, runtime_ticks, 1 if finished else 0, dev[0], dev[1], f'{TAG}-{uuid.uuid4().hex[:8]}', None,
                 int(watched_s * 1000), method, reasons, bitrate, library, vcodec, scodec))
    return stop


def evening_start(day):
    # Evenings and weekend afternoons mostly, a few mornings.
    hour = random.choices([8, 10, 13, 15, 18, 19, 20, 21, 22, 23], weights=[1, 1, 2, 2, 3, 5, 8, 9, 6, 3])[0]
    if day.weekday() >= 5 and random.random() < 0.4:
        hour = random.choice([10, 11, 14, 15, 16, 17])
    local = day.replace(hour=hour, minute=random.randint(0, 59))
    return local


tz = datetime.now().astimezone().tzinfo
for back in range(DAYS, -1, -1):
    day = (datetime.now(tz) - timedelta(days=back)).replace(second=0, microsecond=0)
    busy = 1.6 if day.weekday() >= 4 else 1.0
    for user, devs, weight, taste in profiles:
        if weight == 0 or random.random() > 0.75 * weight * busy:
            continue
        sessions = random.choices([1, 2, 3], weights=[5, 3, 1])[0]
        for _ in range(sessions):
            kind = random.choices(list(taste), weights=list(taste.values()))[0]
            dev = random.choice(devs)
            start = evening_start(day).astimezone(timezone.utc)
            if start > now:
                continue
            method = dev[2] if random.random() < dev[3] else random.choice(['DirectPlay', 'DirectStream', 'Transcode'])
            reasons = random.choice(REASONS) if method == 'Transcode' else None
            if kind == 'show' and episodes:
                ep = random.choice(episodes)
                for k in range(random.choice([1, 1, 2, 3])):
                    e = episodes[(episodes.index(ep) + k) % len(episodes)]
                    run = e.get('RunTimeTicks') or 30 * 60 * 10_000_000
                    full = random.random() < 0.8
                    watched = run / 10_000_000 * (1 if full else random.uniform(0.2, 0.8))
                    vcodec = 'hevc' if method == 'Transcode' and 'Video' in (reasons or '') else 'h264'
                    start = add(user, e, 'show', start, watched, dev, e.get('SeriesId') or e['Id'], e.get('SeriesName') or e['Name'], 'Episode', run,
                                int(run * (1 if full else watched * 10_000_000 / run)), full, lib(e), method, reasons,
                                random.choice([4, 8, 12, 20]) * 1_000_000, vcodec, 'pgssub' if 'Subtitle' in (reasons or '') else None) + timedelta(minutes=random.randint(1, 6))
            elif kind == 'movie' and movies:
                m = random.choice(movies)
                run = m.get('RunTimeTicks') or 100 * 60 * 10_000_000
                full = random.random() < 0.7
                watched = run / 10_000_000 * (1 if full else random.uniform(0.15, 0.7))
                add(user, m, 'movie', start, watched, dev, m['Id'], m['Name'], 'Movie', run, int(run if full else watched * 10_000_000), full, lib(m),
                    method, reasons, random.choice([8, 15, 25, 40]) * 1_000_000, 'hevc' if 'Video' in (reasons or '') else 'h264', 'pgssub' if 'Subtitle' in (reasons or '') else None)
            elif kind == 'music' and songs:
                first = random.randrange(len(songs))
                for k in range(random.randint(4, 14)):
                    sng = songs[(first + k) % len(songs)]
                    run = sng.get('RunTimeTicks') or 200 * 10_000_000
                    start = add(user, sng, 'music', start, run / 10_000_000, dev, sng.get('AlbumId') or sng['Id'], sng.get('Album') or sng['Name'], 'Audio', run, run, True, lib(sng), 'DirectPlay', None, 320_000) + timedelta(seconds=random.randint(0, 20))
            elif kind == 'livetv' and channels:
                c = random.choice(channels)
                add(user, c, 'livetv', start, random.randint(15, 90) * 60, dev, c['Id'], c['Name'], 'TvChannel', 0, 0, False, None, 'Transcode' if random.random() < 0.5 else 'DirectStream', 'ContainerNotSupported', 6_000_000)
            elif kind == 'book' and (audiobooks or books):
                if audiobooks and (not books or random.random() < 0.6):
                    b = random.choice(audiobooks)
                    run = b.get('RunTimeTicks') or 3600 * 10_000_000
                    watched = min(run / 10_000_000, random.randint(20, 70) * 60)
                    add(user, b, 'book', start, watched, dev, b.get('ParentId') or b['Id'], b.get('Album') or b['Name'], 'AudioBook', run, int(watched * 10_000_000), False, lib(b), 'DirectPlay', None, 128_000)
                else:
                    b = random.choice(books)
                    add(user, b, 'book', start, random.randint(10, 60) * 60, dev, b['Id'], b['Name'], 'Book', 10_000_000_000, random.randint(5, 95) * 100_000_000, False, lib(b), 'DirectPlay')

cols = 'id, user_id, item_id, title_id, item_type, title_name, item_name, started_at, stopped_at, position_ticks, runtime_ticks, finished, device_name, client, play_session_id, origin, watched_ms, play_method, transcode_reasons, bitrate, library_id, video_codec, subtitle_codec'
with db:
    gone = db.execute(f"DELETE FROM plays WHERE play_session_id LIKE '{TAG}-%'").rowcount
    db.executemany(f'INSERT INTO plays ({cols}) VALUES ({", ".join("?" * 23)})', rows)
print(f'replaced {gone} seeded plays with {len(rows)} over the last {DAYS} days')
