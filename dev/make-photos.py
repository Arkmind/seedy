#!/usr/bin/env python3
"""Fills the test Photos and Home videos folders with public-domain NASA photographs (images.nasa.gov: Artemis I launch,
Earth from the International Space Station, Mars rovers, Hubble nebulae), so screenshots never show anyone's work.

Each photo is resized to at most 3200 px and saved with a clean EXIF block: the camera, lens and exposure the original
file carries (NASA keeps them), a date taken that sorts the photos into the folders below (made up, so the timeline and
"On this day" have something to show), and a GPS position where it's known (the launch pad, Lisbon seen from orbit).
Folders with photos directly in them become photo albums in Jellyfin; "Night passes" is a folder inside an album.

Two steps, both run by `make dev-media` (from the repository root):
  docker run --rm -v "$PWD/dev/make-photos.py:/gen.py:ro" -v "$PWD/dev/data/media:/media" python:3.12-slim \
    sh -c 'pip install -q --root-user-action=ignore pillow && python /gen.py'
  docker run --rm -v "$PWD/dev/data/media:/media" --entrypoint /bin/sh jellyfin/jellyfin:12.2 /media/.seedy-photos/encode.sh
The first downloads and writes the photos; the second turns a few of them into short pan-and-zoom videos with the Jellyfin
image's ffmpeg. Files that exist are kept: delete one (or a folder) to make it again."""
import io, json, os, urllib.request
from datetime import datetime, timedelta
from pathlib import Path

from PIL import Image, ImageOps
from PIL.TiffImagePlugin import IFDRational

MEDIA = Path(os.environ.get('MEDIA', '/media'))
WORK = MEDIA / '.seedy-photos'
MAX = 3200

KSC = (28.6272, -80.6208)  # Launch Complex 39B, where Artemis I lifted off
LISBON = (38.7105, -9.1335)

# folder, [(NASA id, file name, minutes after the folder's first photo, position)], first photo taken
ALBUMS = [
    ('Photos/Orbit, October 2026', datetime(2026, 10, 2, 8, 14), [
        ('iss043e010521', 'DSC_0412.JPG', 0, None), ('iss042e034845', 'DSC_0419.JPG', 37, None),
        ('iss039e009160', 'DSC_0431.JPG', 95, None),
        ('iss043e000030', 'DSC_0502.JPG', 9 * 1440 + 130, None), ('iss073e0078399', 'DSC_0517.JPG', 9 * 1440 + 162, LISBON),
        ('iss048e056981', 'DSC_0533.JPG', 9 * 1440 + 610, None),
        ('iss030e119777', 'DSC_0611.JPG', 15 * 1440 + 50, None), ('iss040e091231', 'DSC_0618.JPG', 15 * 1440 + 71, None)]),
    ('Photos/Orbit, October 2026/Night passes', datetime(2026, 10, 17, 21, 2), [
        ('iss043e072717', 'DSC_0702.JPG', 0, None), ('iss035e017673', 'DSC_0706.JPG', 6, None),
        ('iss030e010008', 'DSC_0713.JPG', 19, None), ('iss040e091208', 'DSC_0720.JPG', 31, None)]),
    ('Photos/Mars, August 2026', datetime(2026, 8, 15, 10, 30), [
        ('PIA24485', 'PIA24485.JPG', 0, None), ('PIA26479', 'PIA26479.JPG', 48, None), ('PIA25684', 'PIA25684.JPG', 210, None),
        ('PIA19912', 'PIA19912.JPG', 1440 + 20, None), ('PIA16099', 'PIA16099.JPG', 1440 + 75, None),
        ('PIA26402', 'PIA26402.JPG', 1440 + 300, None)]),
    ('Photos/Launch week, November 2025', datetime(2025, 11, 1, 22, 40), [
        ('NHQ202211160016', 'Z6A_1180.JPG', 0, KSC), ('NHQ202211160200', 'Z6A_1244.JPG', 1440 + 190, KSC),
        ('NHQ202211160019', 'L1004127.JPG', 1440 + 191, KSC), ('NHQ202211160003', 'DSC_8811.JPG', 1440 + 193, KSC),
        ('NHQ202211160006', 'DSC_2290.JPG', 1440 + 194, KSC), ('NHQ202211160010', 'IMG_4471.JPG', 1440 + 196, KSC),
        ('NHQ202211160030', 'IMG_9032.JPG', 1440 + 199, KSC), ('NHQ202211160029', 'IMG_9030.JPG', 1440 + 199, KSC)]),
    ('Photos/Nebulae, November 2023', datetime(2023, 11, 4, 23, 5), [
        ('PIA03606', 'Crab.JPG', 0, None), ('PIA04225', 'N44C.JPG', 22, None), ('PIA04227', 'Orion bow shock.JPG', 41, None),
        ('PIA14442', 'Hourglass.JPG', 60, None), ('PIA03096', 'Towering infernos.JPG', 95, None), ('PIA04216', 'Ant.JPG', 130, None),
        ('PIA04200', 'Doradus.JPG', 1440 + 15, None)]),
    # A home videos library with photos next to its videos: the folder is a photo album there too.
    ('Home videos/Rocket park', datetime(2026, 9, 12, 15, 20), [
        ('NHQ202211160005', 'DSC_2291.JPG', 0, KSC), ('NHQ202211160023', 'DSC_2302.JPG', 4, KSC)]),
    ('Home videos', datetime(2026, 9, 20, 11, 0), [('PIA19070', 'PIA19070.JPG', 0, None)]),
]

# Short videos made from photos: (from, to, seconds, minutes after that photo)
VIDEOS = [
    ('Photos/Orbit, October 2026/Night passes/DSC_0720.JPG', 'Photos/Orbit, October 2026/Night passes/DSC_0722.MP4', 14, 3),
    ('Home videos/Rocket park/DSC_2291.JPG', 'Home videos/Rocket park/Liftoff.mp4', 12, 1),
    ('Home videos/Rocket park/DSC_2302.JPG', 'Home videos/Rocket park/Clouds.mp4', 9, 6),
]


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'seedy-dev/1.0'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def original(nasa_id):
    """The biggest JPEG NASA has for the photo."""
    hrefs = [i['href'] for i in json.loads(fetch(f'https://images-api.nasa.gov/asset/{nasa_id}'))['collection']['items']]
    for kind in ('~orig.jpg', '~large.jpg', '~medium.jpg'):
        for h in hrefs:
            if h.lower().endswith(kind):
                return fetch(h.replace('http://', 'https://'))
    raise SystemExit(f'no JPEG for {nasa_id}')


def dms(deg):
    deg = abs(deg)
    d = int(deg)
    m = int((deg - d) * 60)
    s = round(((deg - d) * 60 - m) * 60 * 100)
    return (IFDRational(d, 1), IFDRational(m, 1), IFDRational(s, 100))


# The tags a camera writes that the viewer shows: make, model, lens, exposure, aperture, ISO, focal length.
KEEP_IFD0 = {0x010F, 0x0110}
KEEP_EXIF = {0x829A, 0x829D, 0x8827, 0x920A, 0xA405, 0xA434, 0xA433, 0x8822, 0x9204, 0x9207, 0xA402, 0xA403}


def clean_exif(src, taken, pos):
    old = src.getexif()
    old_exif = old.get_ifd(0x8769)
    exif = Image.Exif()
    for tag in KEEP_IFD0:
        if old.get(tag):
            exif[tag] = str(old[tag]).strip('\x00 ')
    stamp = taken.strftime('%Y:%m:%d %H:%M:%S')
    exif[0x0112] = 1  # the pixels are already upright
    exif[0x0131] = 'Seedy dev photos'
    exif[0x0132] = stamp
    exif[0x013B] = 'NASA'
    exif[0x8298] = 'Public domain, NASA (images.nasa.gov)'
    sub = {}
    for tag in KEEP_EXIF:
        v = old_exif.get(tag)
        if v is not None and v != '':
            sub[tag] = v
    sub[0x9003] = stamp
    sub[0x9004] = stamp
    exif[0x8769] = sub
    if pos:
        gps = {}
        gps[0x0000] = b'\x02\x03\x00\x00'
        gps[0x0001] = 'N' if pos[0] >= 0 else 'S'
        gps[0x0002] = dms(pos[0])
        gps[0x0003] = 'E' if pos[1] >= 0 else 'W'
        gps[0x0004] = dms(pos[1])
        exif[0x8825] = gps
    return exif


def make_photo(nasa_id, dst, taken, pos):
    if dst.exists():
        return False
    dst.parent.mkdir(parents=True, exist_ok=True)
    raw = Image.open(io.BytesIO(original(nasa_id)))
    exif = clean_exif(raw, taken, pos)
    img = ImageOps.exif_transpose(raw).convert('RGB')
    img.thumbnail((MAX, MAX), Image.LANCZOS)
    tmp = dst.with_name('.' + dst.name)
    img.save(tmp, 'JPEG', quality=88, optimize=True, exif=exif.tobytes())
    tmp.rename(dst)
    os.utime(dst, (taken.timestamp(), taken.timestamp()))
    return True


def encode_script():
    lines = ['#!/bin/sh', '# Written by make-photos.py: short pan-and-zoom videos from some of the photos, with the Jellyfin image\'s ffmpeg.',
             'set -e', 'FF=/usr/lib/jellyfin-ffmpeg/ffmpeg', 'cd /media']
    for src, dst, secs, after in VIDEOS:
        taken = next(t + timedelta(minutes=m) for folder, t0, photos in ALBUMS for nid, name, m, _ in photos
                     for t in [t0] if f'{folder}/{name}' == src) + timedelta(minutes=after)
        frames = secs * 25
        vf = (f"scale=3840:-2,zoompan=z='min(1+0.0009*on,1.35)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d={frames}:s=1280x720:fps=25,"
              'format=yuv420p')
        stamp = taken.strftime('%Y-%m-%dT%H:%M:%S.000000Z')
        lines.append(f'[ -f "{dst}" ] || $FF -nostdin -hide_banner -loglevel error -loop 1 -i "{src}" -f lavfi -i anoisesrc=color=brown:amplitude=0.05 '
                     f'-vf "{vf}" -t {secs} -c:v libx264 -preset veryfast -crf 30 -c:a aac -b:a 48k -ac 2 -shortest '
                     f'-metadata creation_time={stamp} "{dst}"')
        lines.append(f'touch -d "{taken.strftime("%Y-%m-%d %H:%M:%S")}" "{dst}"')
    lines += ['cd / && rm -rf /media/.seedy-photos', 'echo "photo videos ready"']
    WORK.mkdir(parents=True, exist_ok=True)
    (WORK / 'encode.sh').write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    made = 0
    for folder, first, photos in ALBUMS:
        for nasa_id, name, minutes, pos in photos:
            made += make_photo(nasa_id, MEDIA / folder / name, first + timedelta(minutes=minutes), pos)
    encode_script()
    print(f'photos ready ({made} new)')
