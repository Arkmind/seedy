#!/usr/bin/env python3
"""Adds the Music and Books libraries to the test Jellyfin (run `make dev-media` first), waits for the scan, then gives
Alex some history so the music and book screens aren't empty: plays, favourites, playlists, reading positions and an
audiobook half listened. Safe to run again: libraries and playlists that exist are kept, the rest is set, not added.
Prints nothing secret."""
import json, time, urllib.parse, urllib.request, urllib.error
from datetime import datetime, timedelta, timezone

JF = 'http://localhost:8096'
CLIENT = 'MediaBrowser Client="Seedy dev", Device="setup-libraries", DeviceId="seedy-dev-setup-libraries", Version="1.0"'
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


def ok(what, status):
    if status >= 300:
        raise SystemExit(f'{what} failed: HTTP {status}')


s, auth = call('POST', '/Users/AuthenticateByName', {'Username': 'Alex', 'Pw': 'seedy'})
ok('sign in as Alex', s)
token, alex = auth['AccessToken'], auth['User']['Id']

# Local metadata only: the artists, albums and books are made up, so online lookups could only find someone else's. Book
# covers come out of the EPUB and CBZ files, which Jellyfin doesn't do unless asked.
def types(*spec):
    return [{'Type': t, 'MetadataFetchers': [], 'MetadataFetcherOrder': [], 'ImageFetchers': img, 'ImageFetcherOrder': img,
             'ImageOptions': [], 'SimilarItemProviders': sim, 'SimilarItemProviderOrder': sim} for t, img, sim in spec]


LOCAL = ['Local Genre/Tag']
LIBRARIES = [
    ('Music', 'music', ['/media/Music'], {
        'EnableLUFSScan': True, 'UseCustomTagDelimiters': True, 'CustomTagDelimiters': [';'],  # "Ada Vale; Oren Moss"
        'TypeOptions': types(('MusicArtist', [], LOCAL), ('MusicAlbum', [], LOCAL), ('Audio', ['Image Extractor'], LOCAL))}),
    ('Books', 'books', ['/media/Books', '/media/Audiobooks', '/media/Comics'], {
        'TypeOptions': types(('Book', ['EPUB Metadata', 'Comic Book Archive Cover Extractor'], []), ('AudioBook', ['Image Extractor'], LOCAL))}),
]


def scanning():
    s, tasks = call('GET', '/ScheduledTasks', query={'isHidden': 'false'})
    s, folders = call('GET', '/Library/VirtualFolders')
    return (any(t.get('Key') == 'RefreshLibrary' and t.get('State') != 'Idle' for t in tasks or [])
            or any(f.get('RefreshStatus') not in (None, 'Idle') for f in folders or []))


def wait_scan():
    time.sleep(5)
    for _ in range(300):
        if not scanning():
            return
        time.sleep(2)
    raise SystemExit('the library scan did not finish')


def same(a, b):
    key = lambda o: json.dumps(o, sort_keys=True)
    return all(key(a.get(k)) == key(v) for k, v in b.items() if k != 'TypeOptions') and \
        key([[t[k] for k in ('Type', 'ImageFetchers', 'SimilarItemProviders', 'MetadataFetchers')] for t in a.get('TypeOptions', [])]) == \
        key([[t[k] for k in ('Type', 'ImageFetchers', 'SimilarItemProviders', 'MetadataFetchers')] for t in b['TypeOptions']])


s, folders = call('GET', '/Library/VirtualFolders')
ok('list libraries', s)
have = {f['Name']: f for f in folders}
added, refresh = False, []
for name, kind, paths, options in LIBRARIES:
    if name in have:
        lib = have[name]
        if same(lib['LibraryOptions'], options):
            print(name, 'library already there')
        else:  # older options: update them, then look at every item again (covers, artists)
            s, _ = call('POST', '/Library/VirtualFolders/LibraryOptions', {'Id': lib['ItemId'], 'LibraryOptions': {**lib['LibraryOptions'], **options}})
            print(name, 'library options updated', s)
            refresh.append(lib['ItemId'])
        continue
    opts = {'Enabled': True, 'EnableRealtimeMonitor': True, 'SaveLocalMetadata': False, 'PreferredMetadataLanguage': 'en',
            'MetadataCountryCode': 'GB', 'PathInfos': [{'Path': p} for p in paths], **options}
    s, _ = call('POST', '/Library/VirtualFolders', {'LibraryOptions': opts},
                {'name': name, 'collectionType': kind, 'paths': paths, 'refreshLibrary': 'false'})
    print(name, 'library added', s)
    added = True
if added:
    call('POST', '/Library/Refresh')
for item in refresh:
    call('POST', f'/Items/{item}/Refresh', query={'Recursive': 'true', 'MetadataRefreshMode': 'FullRefresh', 'ImageRefreshMode': 'FullRefresh',
                                                  'ReplaceAllMetadata': 'false', 'ReplaceAllImages': 'false'})
if added or refresh:
    print('scanning…')
    wait_scan()
s, folders = call('GET', '/Library/VirtualFolders')
libs = {f['Name']: f['ItemId'] for f in folders}
print('libraries', {n: libs.get(n) for n in ('Music', 'Books')})


# ---------------------------------------------------------------------------------------------------------------------
# Alex's history. Everything is set to fixed values (not added to), so running again changes nothing.

now = datetime.now(timezone.utc).replace(microsecond=0)
iso = lambda dt: dt.strftime('%Y-%m-%dT%H:%M:%S.0000000Z')


def items(kind, parent, **q):
    s, r = call('GET', '/Items', query={'userId': alex, 'ParentId': parent, 'Recursive': 'true', 'IncludeItemTypes': kind,
                                        'Fields': 'Path,DateCreated', 'SortBy': 'SortName', **q})
    ok('list ' + kind, s)
    return r['Items']


def set_item(item, **changes):
    """Edits an item like the metadata editor does: the whole item back, with these fields changed."""
    s, dto = call('GET', f'/Items/{item["Id"]}', query={'userId': alex})
    ok('read ' + item['Name'], s)
    if all(str(dto.get(k, ''))[:19] == str(v)[:19] for k, v in changes.items()):
        return
    ok('edit ' + item['Name'], call('POST', f'/Items/{item["Id"]}', {**dto, **changes})[0])


def user_data(item, **data):
    ok('user data for ' + item['Name'], call('POST', f'/UserItems/{item["Id"]}/UserData', data, {'userId': alex})[0])


tracks = items('Audio', libs['Music'])
albums = {(a.get('AlbumArtist'), a['Name']): a for a in items('MusicAlbum', libs['Music'])}
album_tracks = {}
for t in tracks:
    album_tracks.setdefault((t.get('AlbumArtist'), t.get('Album')), []).append(t)
for k in album_tracks:
    album_tracks[k].sort(key=lambda t: (t.get('ParentIndexNumber') or 1, t.get('IndexNumber') or 0))


def track(album, title, artist='Ada Vale'):
    return next(t for t in album_tracks[(artist, album)] if t['Name'] == title)


# Added to the server: the newest first, in the boards' order ("Recently added" rows); Northline on 2 April 2024.
added = [('Field Recordings', 'Ada Vale'), ('Blue Hour', 'Lumen Fields'), ('Copper Line', 'The Quiet Engine'), ('Harbor Lights', 'Harbor Lights'),
         ('Small Hours', 'Kestrel Choir'), ('Undertow', 'Oren Moss'), ('Static Bloom', 'Various Artists'),
         ('Night Ferry (Original Score)', 'Oren Moss'), ('Salt Hymns', 'Mira Sol'), ('Glass Weather', 'Ada Vale'), ('Halden', 'Ada Vale'),
         ('Winter Radio', 'Ada Vale'), ('Small Harbours', 'Ada Vale')]
dates = {k: now - timedelta(days=1 + 2 * i, hours=3) for i, k in enumerate(added)}
dates[('Northline', 'Ada Vale')] = datetime(2024, 4, 2, 9, 30, tzinfo=timezone.utc)
for (album, artist), when in dates.items():
    set_item(albums[(artist, album)], DateCreated=iso(when))
    for t in album_tracks[(artist, album)]:
        set_item(t, DateCreated=iso(when))
print('music: dates added set')

# Plays: the numbers on the Album and Artist boards for Northline, then less for everything else. Undertow and Static Bloom
# stay unplayed ("Something new").
NORTHLINE = [312, 1204, 688, 402, 851, 277, 190, 233, 540, 201, 176, 298]
PER_ARTIST = {'Lumen Fields': 87, 'The Quiet Engine': 71, 'Oren Moss': 25, 'Harbor Lights': 50, 'Kestrel Choir': 42, 'Mira Sol': 33, 'Ada Vale': 30}
RECENT = {'Northline': 0, 'Blue Hour': 2, 'Field Recordings': 5, 'Copper Line': 26, 'Night Ferry (Original Score)': 30, 'Winter Radio': 50}
plays = 0
for (artist, album), ts in album_tracks.items():
    if album in ('Undertow', 'Static Bloom'):
        continue
    for n, t in enumerate(ts):
        count = NORTHLINE[n] if album == 'Northline' else (612 if t['Name'] == 'Winter Radio' else
                                                            max(1, PER_ARTIST.get(artist, 10) + (n * 37) % 23 - 11))
        hours = RECENT.get(album, 24 * (3 + (n * 5 + len(album)) % 40)) + n * 0.1
        user_data(t, PlayCount=count, Played=True, LastPlayedDate=iso(now - timedelta(hours=hours)))
        plays += 1
print('music:', plays, 'songs played')

FAVOURITE_SONGS = [('Northline', 'Northline'), ('Northline', 'Glass Weather'), ('Northline', 'Lanterns'), ('Winter Radio', 'Winter Radio'),
                   ('Small Harbours', 'Small Harbours'), ('Field Recordings', 'Upright Piano')]
favourites = [track(a, n) for a, n in FAVOURITE_SONGS] + album_tracks[('Lumen Fields', 'Blue Hour')][:3] + \
    album_tracks[('The Quiet Engine', 'Copper Line')][:2] + album_tracks[('Harbor Lights', 'Harbor Lights')][:2]
for t in favourites:
    user_data(t, IsFavorite=True)
for key in [('Ada Vale', 'Northline'), ('Lumen Fields', 'Blue Hour'), ('The Quiet Engine', 'Copper Line')]:
    user_data(albums[key], IsFavorite=True)
s, artists = call('GET', '/Artists/AlbumArtists', query={'userId': alex, 'ParentId': libs['Music']})
for a in artists['Items']:
    if a['Name'] in ('Ada Vale', 'Lumen Fields'):
        user_data(a, IsFavorite=True)
print('music:', len(favourites), 'favourite songs, 3 albums, 2 artists')

# Playlists: the first songs pick the albums in each playlist's cover, as on the Music board.
A = lambda artist, album, *idx: [album_tracks[(artist, album)][i] for i in (idx or range(len(album_tracks[(artist, album)])))]
PLAYLISTS = {
    'Late night': A('Ada Vale', 'Halden', 0) + A('Ada Vale', 'Glass Weather', 1) + A('Various Artists', 'Static Bloom', 0) + A('Ada Vale', 'Northline', 1, 7)
    + A('Ada Vale', 'Field Recordings') + A('Oren Moss', 'Undertow') + A('Kestrel Choir', 'Small Hours', 0, 5) + A('Oren Moss', 'Night Ferry (Original Score)', 2, 4, 7),
    'Your favourites': favourites[:1] + A('Ada Vale', 'Small Harbours', 0) + A('The Quiet Engine', 'Copper Line', 0) + A('Ada Vale', 'Winter Radio', 0) + favourites[1:],
    'Sunday morning': A('Ada Vale', 'Field Recordings', 2) + A('Kestrel Choir', 'Small Hours', 1) + A('Ada Vale', 'Small Harbours', 1, 3)
    + A('Lumen Fields', 'Blue Hour', 6) + A('Mira Sol', 'Salt Hymns') + A('Ada Vale', 'Northline', 0, 2, 11),
    'Road trip': A('The Quiet Engine', 'Copper Line', 2) + A('Harbor Lights', 'Harbor Lights') + A('Ada Vale', 'Halden', 1) + A('Ada Vale', 'Field Recordings', 6)
    + A('Lumen Fields', 'Blue Hour') + A('Ada Vale', 'Winter Radio') + A('The Quiet Engine', 'Copper Line'),
    'Focus': A('Kestrel Choir', 'Small Hours', 2) + A('Various Artists', 'Static Bloom', 1, 3, 5) + A('Ada Vale', 'Northline', 6) + A('Oren Moss', 'Undertow', 0, 2)
    + A('Ada Vale', 'Field Recordings', 0, 1, 4) + A('Lumen Fields', 'Blue Hour', 5),
}
s, existing = call('GET', '/Items', query={'userId': alex, 'IncludeItemTypes': 'Playlist', 'Recursive': 'true'})
have_lists = {p['Name']: p for p in existing['Items']}
for i, (name, songs) in enumerate(PLAYLISTS.items()):
    if name not in have_lists:
        s, r = call('POST', '/Playlists', {'Name': name, 'Ids': [t['Id'] for t in songs], 'UserId': alex, 'MediaType': 'Audio', 'IsPublic': False})
        ok('playlist ' + name, s)
        have_lists[name] = {'Id': r['Id'], 'Name': name}
        print('playlist', name, len(songs), 'songs')
    else:
        print('playlist', name, 'already there')
    user_data(have_lists[name], LastPlayedDate=iso(now - timedelta(hours=[1, 4, 12, 30, 52][i])), PlayCount=3 + i)

# Books: added dates in the boards' order, then where Alex is in each one.
books = items('Book,AudioBook', libs['Books'])
kind = lambda b: 'audio' if b['Type'] == 'AudioBook' else b['Path'].rsplit('.', 1)[-1].lower()
book = lambda title, k='epub': next(b for b in books if b['Name'] == title and kind(b) == k)
RECENT_BOOKS = {
    'epub': ['Salt & Signal', 'Small Harbours', 'The Quiet Engine', 'Lantern Season', 'Copper Line', 'North of Nowhere', 'Field Notes on Leaving',
             'The Undercroft', 'A Field Guide to Thaw', 'Notes from Halden', 'Black Ice'],
    'audio': ['A Field Guide to Fog', 'The Glass Orchard', 'Copper Line', 'Salt & Signal', 'Small Harbours', 'Night Ferry', 'The Undercroft'],
    'cbz': ['Kestrel No. 5', 'Kestrel No. 4', 'Tidewater', 'Ember Run Vol. 2', 'Static Bloom', 'Low Orbit Vol. 1', 'Harbor Lights Vol. 3', 'Paper Moon Vol. 1'],
}
for k, titles in RECENT_BOOKS.items():
    for i, title in enumerate(titles):
        set_item(book(title, k), DateCreated=iso(now - timedelta(days=1 + 3 * i, hours=5)))
rest = [b for b in books if b['Name'] not in RECENT_BOOKS.get(kind(b), [])]
for i, b in enumerate(rest):
    set_item(b, DateCreated=iso(now - timedelta(days=60 + 9 * i)))
glass = book('The Glass Orchard')
# Calibre's rating is out of 10 and Jellyfin rounds it to a whole number: put back the board's 4.3 stars (8.6), as an edit would.
for b in (glass, book('The Glass Orchard', 'pdf')):
    set_item(b, DateCreated='2025-01-03T10:00:00.0000000Z', CommunityRating=8.6)
print('books: dates added set')

reading = [(book(t), {'percent': 100, 'finished': True}) for t in ('Halden', 'The Long Frost', 'Coldwater')]
reading += [(book(f'Kestrel No. {n}', 'cbz'), {'locator': str(c - 1), 'percent': 100, 'finished': True}) for n, c in ((1, 12), (2, 12), (3, 12))]
reading += [(book('Kestrel No. 4', 'cbz'), {'locator': '11', 'percent': 37})]
for b, place in reading:
    ok('reading ' + b['Name'], call('POST', f'/Seedy/Reading/{b["Id"]}', {'locator': '', 'finished': False, **place})[0])

# A Field Guide to Fog, 58% listened (in chapter 7, The Lighthouse): a playback that stopped there.
fog = book('A Field Guide to Fog', 'audio')
s, fog = call('GET', f'/Items/{fog["Id"]}', query={'userId': alex})
pos = int(fog['RunTimeTicks'] * 0.58)
session = {'ItemId': fog['Id'], 'PlaySessionId': 'seedy-dev-fog', 'PositionTicks': pos - 300_000_000, 'CanSeek': True, 'PlayMethod': 'DirectPlay'}
ok('start the audiobook', call('POST', '/Sessions/Playing', session)[0])
ok('stop the audiobook', call('POST', '/Sessions/Playing/Stopped', {**session, 'PositionTicks': pos})[0])
user_data(fog, PlayCount=4, PlaybackPositionTicks=pos, LastPlayedDate=iso(now - timedelta(minutes=40)))

ok('reading The Glass Orchard', call('POST', f'/Seedy/Reading/{glass["Id"]}', {'locator': '', 'percent': 46, 'chapter': 'Frost', 'chapterIndex': 12,
                                                                                  'chapterCount': 28, 'finished': False})[0])
print('books: reading The Glass Orchard (46%), Kestrel No. 4 (page 12), listening to A Field Guide to Fog (58%);',
      'finished Halden, The Long Frost, Coldwater, Kestrel No. 1-3')
