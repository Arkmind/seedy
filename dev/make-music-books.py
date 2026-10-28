#!/usr/bin/env python3
"""Fills the test library's Music, Books, Audiobooks and Comics folders with made-up titles, all generated here (songs,
cover art, book text, comic pages), so screenshots never show anyone's work. Names follow the design boards.

Two steps, both run by `make dev-media` (from dev/):
  docker run --rm -v "$PWD/make-music-books.py:/gen.py:ro" -v "$PWD/../android/ui/src/main/res/font/noto_sans.ttf:/font.ttf:ro" \
    -v "$PWD/data/media:/media" python:3.12-slim sh -c 'pip install -q --root-user-action=ignore pillow numpy && python /gen.py'
  docker run --rm -v "$PWD/data/media:/media" --entrypoint /bin/sh jellyfin/jellyfin:12.2 /media/.seedy-audio/encode.sh
The first writes the books (EPUB, PDF), comics (CBZ with ComicInfo.xml), cover art, lyrics and NFO files, and the songs
and audiobooks as WAV with an encode script; the second encodes those with the Jellyfin image's ffmpeg (FLAC, MP3, M4B
with chapters and tags) and removes the WAVs. Files that exist are kept: delete one (or a folder) to make it again."""
import math, os, shlex, struct, uuid, zipfile, zlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

MEDIA = Path(os.environ.get('MEDIA', '/media'))
WORK = MEDIA / '.seedy-audio'
FONT = os.environ.get('FONT', '/font.ttf')
_fonts = {}


def font(px, weight=900):
    key = (int(px), weight)
    if key not in _fonts:
        f = ImageFont.truetype(FONT, int(px))
        f.set_variation_by_axes([weight])
        _fonts[key] = f
    return _fonts[key]


def seed_of(*parts):
    return zlib.crc32('|'.join(map(str, parts)).encode())


def xml(s):
    return str(s).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')


# ---------------------------------------------------------------------------------------------------------------------
# Cover art, drawn like the boards' artwork: flat colours, a sun with a ring, a band, waves or mountains.

CP = [  # square art (albums, audiobooks): background, band, sun, ink
    ('#1c2a3a', '#2f4a66', '#f2c47e', '#e8eef4'), ('#3a1d2e', '#6b2f4f', '#ff9fbf', '#fbe4f3'), ('#13302b', '#1f5a4d', '#9fe0c8', '#def4f1'),
    ('#2b2118', '#5c3b22', '#f0a35e', '#f7ecd5'), ('#1d1d2e', '#3b3b6b', '#b9b3ff', '#efe6fb'), ('#262626', '#444444', '#f0f0f0', '#ffffff'),
    ('#36200f', '#a3471f', '#ffd27a', '#fff2d8'), ('#0f2233', '#1b6f8f', '#9fe9ff', '#e6fbff'), ('#2a1035', '#7a3fd1', '#ffb3e6', '#fbe4f3')]
BP = [  # book covers: background, sun, ground, ink
    ('#e8dcc8', '#c9583e', '#2c3a49', '#1e1e1e'), ('#1f3a3c', '#a8e6df', '#0f1c1d', '#def4f1'), ('#2f2840', '#e3c4ff', '#16121f', '#efe6fb'),
    ('#f2cf86', '#1d170f', '#5c4a2c', '#1d170f'), ('#3d2424', '#f0b9a0', '#1c1111', '#f6e3da'), ('#dfe7ef', '#2f4a7a', '#9fb2c6', '#141b22'),
    ('#26362b', '#d9e3b5', '#121a14', '#eef2e0'), ('#141414', '#ff9fff', '#2a2a2a', '#f0f0f0')]


def quad(p0, c, p1, n=32):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1])
            for t in (i / n for i in range(n + 1))]


CPATH = [  # in a 200 x 200 box
    [(0, 140)] + quad((0, 140), (50, 110), (100, 140)) + quad((100, 140), (150, 170), (200, 140)) + [(200, 200), (0, 200)],
    [(0, 200), (200, 70), (200, 200)],
    [(0, 150), (50, 110), (90, 140), (140, 90), (200, 130), (200, 200), (0, 200)],
    [(0, 0), (200, 0), (200, 40)] + quad((200, 40), (100, 70), (0, 40))]
BPATH = [  # in a 200 x 300 box
    [(0, 300), (0, 210)] + quad((0, 210), (100, 170), (200, 210)) + [(200, 300)],
    [(0, 300), (200, 160), (200, 300)],
    [(0, 230), (50, 190), (100, 220), (150, 170), (200, 200), (200, 300), (0, 300)],
    [(0, 300), (0, 240), (200, 240), (200, 300)]]


def rgba(hex_, a=1.0):
    return tuple(int(hex_[i:i + 2], 16) for i in (1, 3, 5)) + (round(255 * a),)


def wrap(text, f, width):
    lines, line = [], ''
    for word in text.split():
        trial = (line + ' ' + word).strip()
        if line and f.getlength(trial) > width:
            lines.append(line)
            line = word
        else:
            line = trial
    return lines + ([line] if line else [])


def spaced(draw, xy, text, f, fill, spacing):
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=f, fill=fill, anchor='ls')
        x += f.getlength(ch) + spacing


def square_art(i, text, size=600):
    """Album or audiobook art: the boards' 'cov' artwork, the title at the bottom left."""
    S = 2
    W = size * S
    k = W / 200
    bg, band, sun, ink = CP[i % len(CP)]
    s = i % 4
    img = Image.new('RGBA', (W, W), rgba(bg))
    d = ImageDraw.Draw(img)
    by, bh = (120, 80) if s == 1 else ((0, 30) if s == 3 else (200, 0))
    if bh:
        d.rectangle([0, by * k, W, (by + bh) * k], fill=rgba(band))
    cx, cy, r, r2 = [70, 130, 100, 60][s], [80, 70, 95, 120][s], [46, 30, 58, 22][s], [70, 52, 82, 40][s]
    sw, ro, po = [3, 8, 2, 6][s], [0.5, 0.35, 0.6, 0.4][s], [0.9, 0.7, 0.85, 0.6][s]
    d.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=rgba(sun))
    over = Image.new('RGBA', img.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(over)
    h = sw * k / 2
    od.ellipse([(cx - r2) * k - h, (cy - r2) * k - h, (cx + r2) * k + h, (cy + r2) * k + h], outline=rgba(ink, ro), width=round(sw * k))
    img = Image.alpha_composite(img, over)
    over = Image.new('RGBA', img.size, (0, 0, 0, 0))
    ImageDraw.Draw(over).polygon([(x * k, y * k) for x, y in CPATH[(i + 1) % 4]], fill=rgba(band, po))
    img = Image.alpha_composite(img, over)
    if text:
        d = ImageDraw.Draw(img)
        f = font(W * 0.105)
        lines = wrap(text.upper(), f, W * 0.84)
        lh = W * 0.105 * 0.95
        y = W * 0.93 - lh * (len(lines) - 1)
        for line in lines:
            d.text((W * 0.08, y), line, font=f, fill=rgba(ink), anchor='ls')
            y += lh
    return img.resize((size, size), Image.LANCZOS).convert('RGB')


def book_art(i, title, author, w=600, h=900):
    """A book cover like the boards' 'bk' artwork: the title at the top, the author at the bottom."""
    S = 2
    W, H = w * S, h * S
    k = W / 200
    bg, sun, ground, ink = BP[i % len(BP)]
    s = i % 4
    img = Image.new('RGB', (W, H), bg)
    d = ImageDraw.Draw(img)
    cx, cy, r = [140, 60, 100, 150][s], [150, 170, 130, 120][s], [44, 60, 36, 50][s]
    d.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=sun)
    d.polygon([(x * k, y * k) for x, y in BPATH[s]], fill=ground)
    f = font(W * 0.13)
    y = H * 0.09 + W * 0.13 * 0.8
    for line in wrap(title.upper(), f, W * 0.78):
        d.text((W * 0.11, y), line, font=f, fill=ink, anchor='ls')
        y += W * 0.13
    if author:
        fa = font(W * 0.068, 800)
        r_, g_, b_ = rgba(ink)[:3]
        br, bgc, bb = rgba(bg)[:3]
        mix = tuple(round(c * 0.85 + o * 0.15) for c, o in zip((r_, g_, b_), (br, bgc, bb)))
        spaced(d, (W * 0.11, H * 0.93), author.upper(), fa, mix, W * 0.068 * 0.12)
    return img.resize((w, h), Image.LANCZOS)


def jpeg(img, path, q=88):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, 'JPEG', quality=q, optimize=True)


def jpeg_bytes(img, q=88):
    import io
    b = io.BytesIO()
    img.save(b, 'JPEG', quality=q, optimize=True)
    return b.getvalue()


# ---------------------------------------------------------------------------------------------------------------------
# Music: soft generated songs (pads, plucked arpeggios, bass), a little different per genre.

def midi(m):
    return 440.0 * 2 ** ((m - 69) / 12)


MAJOR, MINOR = [0, 2, 4, 5, 7, 9, 11], [0, 2, 3, 5, 7, 8, 10]


def degree(scale, root, d):
    return root + 12 * (d // 7) + scale[d % 7]


def env_ar(n, sr, attack, release):
    t = np.arange(n) / sr
    a = np.clip(t / max(attack, 1e-3), 0, 1)
    r = np.clip((n / sr - t) / max(release, 1e-3), 0, 1)
    return (0.5 - 0.5 * np.cos(np.pi * a)) * (0.5 - 0.5 * np.cos(np.pi * r))


def pluck(f, dur, sr, bright=1.0, decay=2.5):
    n = int(dur * sr)
    t = np.arange(n) / sr
    w = 2 * np.pi * f * t
    y = np.sin(w) + 0.45 * bright * np.sin(2 * w) * np.exp(-2 * t) + 0.2 * bright * np.sin(3 * w) * np.exp(-4 * t) \
        + 0.08 * bright * np.sin(4 * w) * np.exp(-7 * t)
    e = np.exp(-decay * t) * np.clip(t / 0.004, 0, 1) * np.clip((dur - t) / 0.05, 0, 1)
    return y * e


def bell(f, dur, sr):
    n = int(dur * sr)
    t = np.arange(n) / sr
    w = 2 * np.pi * f * t
    y = np.sin(w) + 0.4 * np.sin(2.76 * w) * np.exp(-1.5 * t) + 0.2 * np.sin(5.4 * w) * np.exp(-3 * t)
    return y * np.exp(-1.2 * t) * np.clip(t / 0.003, 0, 1) * np.clip((dur - t) / 0.1, 0, 1)


def pad(f, dur, sr, attack=0.9, release=1.2, choir=False):
    n = int(dur * sr)
    t = np.arange(n) / sr
    vib = (1 + 0.003 * np.sin(2 * np.pi * 5.2 * t)) if choir else 1
    w = 2 * np.pi * f * t * vib
    y = np.sin(w) + 0.7 * np.sin(w * 1.004 + 1.3) + 0.25 * np.sin(2 * w + 0.4) + (0.18 * np.sin(3 * w) if choir else 0)
    return y * env_ar(n, sr, attack, release) * (1 + 0.08 * np.sin(2 * np.pi * 0.3 * t))


def bassnote(f, dur, sr):
    n = int(dur * sr)
    t = np.arange(n) / sr
    w = 2 * np.pi * f * t
    return (np.sin(w) + 0.25 * np.sin(2 * w)) * np.exp(-1.6 * t) * np.clip(t / 0.01, 0, 1) * np.clip((dur - t) / 0.06, 0, 1)


def kick(sr):
    n = int(0.4 * sr)
    t = np.arange(n) / sr
    f = 45 + 80 * np.exp(-25 * t)
    return np.sin(2 * np.pi * np.cumsum(f) / sr) * np.exp(-9 * t)


def tick(sr, rng, dur=0.06):
    n = int(dur * sr)
    t = np.arange(n) / sr
    noise = np.diff(rng.standard_normal(n + 1))
    return noise * np.exp(-60 * t) * 0.5


def add(out, sr, at, y, gain):
    s = int(at * sr)
    if s >= len(out):
        return
    m = min(len(y), len(out) - s)
    out[s:s + m] += gain * y[:m]


STYLES = {  # bpm, scale, progressions (scale degrees), arpeggio notes per beat
    'folk': (84, MAJOR, [[0, 4, 5, 3], [0, 5, 3, 4], [5, 3, 0, 4], [0, 3, 5, 4]], 2),
    'chamber': (72, MAJOR, [[0, 5, 3, 4], [3, 0, 4, 5], [0, 2, 3, 4]], 2),
    'ambient': (52, MAJOR, [[0, 3], [5, 3], [0, 4]], 0),
    'electronic': (116, MINOR, [[0, 5, 2, 6], [0, 3, 5, 4], [5, 3, 0, 6]], 4),
    'jazz': (92, MAJOR, [[1, 4, 0, 0], [1, 4, 0, 5], [3, 2, 1, 4]], 0),
    'classical': (60, MAJOR, [[0, 3, 4, 0], [5, 3, 4, 0], [0, 5, 1, 4]], 0),
    'soundtrack': (64, MINOR, [[0, 5, 2, 6], [0, 3, 0, 4], [5, 6, 0, 0]], 1),
}


def song(seconds, sr, style, seed):
    rng = np.random.default_rng(seed)
    bpm, scale, progs, arp = STYLES[style]
    bpm *= rng.uniform(0.92, 1.08)
    beat = 60 / bpm
    bar = beat * 4 * (2 if style == 'ambient' else 1)
    root = 50 + int(rng.integers(0, 10))  # D3 .. B3
    prog = progs[int(rng.integers(len(progs)))]
    out = np.zeros(int(seconds * sr) + sr)
    penta = [0, 2, 4, 7, 9] if scale is MAJOR else [0, 3, 5, 7, 10]
    nbars = int(math.ceil(seconds / bar))
    for b in range(nbars):
        t0 = b * bar
        dg = prog[b % len(prog)]
        seventh = style == 'jazz'
        chord = [degree(scale, root, dg + 2 * j) for j in range(4 if seventh else 3)]
        voicing = [m + 12 for m in chord]
        # pad (strings, choir or a soft synth)
        if style in ('folk', 'chamber', 'ambient', 'electronic', 'classical', 'soundtrack'):
            g = {'folk': 0.05, 'chamber': 0.1, 'ambient': 0.11, 'electronic': 0.06, 'classical': 0.12, 'soundtrack': 0.1}[style]
            for m in (voicing if style != 'classical' else [chord[0], chord[1] + 12, chord[2] + 12, chord[0] + 24]):
                add(out, sr, t0, pad(midi(m - (12 if style == 'soundtrack' else 0)), bar + 1.0, sr, choir=style == 'classical'), g)
        # bass
        if style in ('folk', 'chamber', 'soundtrack', 'classical'):
            add(out, sr, t0, bassnote(midi(chord[0] - 12), bar * 0.9, sr), 0.22)
            if style in ('folk', 'chamber'):
                add(out, sr, t0 + 2 * beat, bassnote(midi(chord[0] - 12 + (7 if b % 2 else 0)), bar * 0.45, sr), 0.16)
        elif style == 'electronic':
            for q in range(8):
                add(out, sr, t0 + q * beat / 2, bassnote(midi(chord[0] - 12), beat / 2 * 0.9, sr), 0.18)
        elif style == 'jazz':  # walking bass, four to the bar
            walk = [chord[0], chord[1], chord[2], chord[2] + 2]
            for q in range(4):
                add(out, sr, t0 + q * beat, bassnote(midi(walk[q] - 12), beat * 0.95, sr), 0.26)
        # arpeggio / comping / melody
        if style == 'jazz':
            for at in (0, 1.5, 2.5 if b % 2 else 3):
                for m in voicing:
                    add(out, sr, t0 + at * beat, pluck(midi(m), 1.6, sr, bright=0.6, decay=1.8), 0.06)
            for q in range(8):
                if rng.random() < 0.45:
                    add(out, sr, t0 + q * beat / 2, pluck(midi(degree(scale, root + 12, int(rng.integers(0, 7)))), 0.7, sr, 0.8, 3.0), 0.09)
            for q in (1, 3):
                add(out, sr, t0 + q * beat, tick(sr, rng, 0.12), 0.05)
        elif arp:
            step = beat / arp
            pattern = [0, 1, 2, 1, 2, 3, 2, 1]
            for q in range(int(4 * arp * (2 if style == 'ambient' else 1))):
                m = (voicing + [voicing[0] + 12])[pattern[q % 8] % 4]
                if style == 'soundtrack' and rng.random() < 0.5:
                    continue
                add(out, sr, t0 + q * step, pluck(midi(m), step * 4, sr, bright=1.2 if style == 'electronic' else 0.9,
                                                  decay=6 if style == 'electronic' else 2.6), 0.11 if style != 'electronic' else 0.08)
        if style in ('folk', 'chamber', 'soundtrack', 'classical'):  # a melody over every other bar
            if b % 2 == 1 or style == 'classical':
                for q in range(4):
                    if rng.random() < 0.7:
                        m = root + 24 + penta[int(rng.integers(0, 5))]
                        add(out, sr, t0 + q * beat, (pad(midi(m), beat * 1.2, sr, 0.1, 0.4, choir=True) * 0.6) if style == 'classical'
                            else pluck(midi(m), beat * 2.5, sr, 0.7, 1.8), 0.1)
        if style == 'ambient':
            for q in range(8):
                if rng.random() < 0.3:
                    add(out, sr, t0 + q * beat + rng.uniform(0, beat / 2), bell(midi(root + 24 + penta[int(rng.integers(0, 5))]), 3.0, sr), 0.07)
        if style == 'electronic':
            for q in range(4):
                add(out, sr, t0 + q * beat, kick(sr), 0.35)
                add(out, sr, t0 + q * beat + beat / 2, tick(sr, rng), 0.06)
        if style == 'soundtrack' and b % 2 == 0:
            add(out, sr, t0, bassnote(midi(root - 24), 2.5, sr), 0.3)
    out = out[:int(seconds * sr)]
    # a small room: a few quiet echoes
    wet = np.zeros_like(out)
    for dly, g in ((0.113, 0.22), (0.241, 0.16), (0.397, 0.11), (0.61, 0.07)):
        s = int(dly * sr)
        wet[s:] += g * out[:-s]
    out = out + wet
    out /= max(1e-9, np.abs(out).max()) / 0.62
    return out * env_ar(len(out), sr, 1.2, 3.0)


VOWELS = [(730, 1090), (530, 1840), (270, 2290), (570, 840), (300, 870), (660, 1720), (440, 1020), (390, 2300)]


def narration(seconds, sr, seed, f0):
    """Murmured 'speech': voiced syllables with vowel formants, words and sentences, a falling pitch per sentence."""
    rng = np.random.default_rng(seed)
    out = np.zeros(int(seconds * sr) + sr)
    pos, sentence = 0.5, 0
    while pos < seconds - 0.8:
        for _ in range(int(rng.integers(1, 4))):
            d = rng.uniform(0.1, 0.22)
            n = int(d * sr)
            t = np.arange(n) / sr
            fall = 1 - 0.12 * min(sentence, 12) / 12
            base = f0 * fall * (1 + 0.06 * rng.standard_normal())
            fr = base * (1 + 0.04 * np.sin(np.pi * t / d) - 0.03 * t / d)
            ph = 2 * np.pi * np.cumsum(fr) / sr
            F1, F2 = VOWELS[int(rng.integers(len(VOWELS)))]
            ks = np.arange(1, int(3600 / base) + 1)
            amp = (np.exp(-((ks * base - F1) / 140) ** 2) + 0.6 * np.exp(-((ks * base - F2) / 200) ** 2) + 0.04) / np.sqrt(ks)
            y = (amp[:, None] * np.sin(ks[:, None] * ph[None, :])).sum(axis=0)
            y *= np.sin(np.pi * t / d) ** 1.2
            add(out, sr, pos, y, 0.25)
            if rng.random() < 0.5:  # a consonant before the next syllable
                c = int(0.025 * sr)
                add(out, sr, pos + d, np.diff(rng.standard_normal(c + 1)) * np.hanning(c), 0.05)
            pos += d + rng.uniform(0.0, 0.03)
        pos += rng.uniform(0.06, 0.13)
        sentence += 1
        if rng.random() < 0.13:
            pos += rng.uniform(0.35, 0.75)
            sentence = 0
    out = out[:int(seconds * sr)]
    out /= max(1e-9, np.abs(out).max()) / 0.55
    return out


def write_wav(path, x, sr):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = np.asarray(x, dtype='<f4').tobytes()
    with open(path, 'wb') as fh:
        fh.write(b'RIFF' + struct.pack('<I', 36 + len(data)) + b'WAVE')
        fh.write(b'fmt ' + struct.pack('<IHHIIHH', 16, 3, 1, sr, sr * 4, 4, 32))
        fh.write(b'data' + struct.pack('<I', len(data)) + data)


jobs = []


def job(dst, wav, cover, fmt, tags, chapters=None):
    """One ffmpeg command for the encode script: WAV + cover (+ chapters) to the final file, written next to it first."""
    tmp = WORK / f'job{len(jobs):03d}.part'
    cmd = ['$FF', '-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(wav), '-i', str(cover)]
    if chapters:
        cmd += ['-i', str(chapters), '-map_metadata', '2', '-map_chapters', '2']
    cmd += ['-map', '0:a', '-map', '1:v', '-c:v', 'copy', '-disposition:v:0', 'attached_pic', '-metadata:s:v', 'comment=Cover (front)']
    cmd += {'flac': ['-c:a', 'flac', '-ar', '96000', '-sample_fmt', 's32', '-bits_per_raw_sample', '24', '-ac', '2', '-f', 'flac'],
            'mp3': ['-c:a', 'libmp3lame', '-q:a', '6', '-ac', '2', '-ar', '44100', '-f', 'mp3'],
            'm4b': ['-c:a', 'aac', '-b:a', '32k', '-ac', '1', '-ar', '22050', '-brand', 'M4B ', '-f', 'ipod']}[fmt]
    for k, v in tags.items():
        if v not in (None, ''):
            cmd += ['-metadata', f'{k}={v}']
    q = lambda a: a if a == '$FF' else shlex.quote(a)
    jobs.append(' '.join(q(a) for a in cmd + [str(tmp)]) + f' && mv {shlex.quote(str(tmp))} {shlex.quote(str(dst))} && rm -f {shlex.quote(str(wav))}')


ADA = 'Ada Vale'
MUSIC = [
    dict(artist=ADA, album='Northline', year=2024, date='2024-03-12', art=0, genre='Indie folk', style='folk', fmt='flac',
         label='Hollow Pine Records', tracks=['First Light', 'Northline', 'Paper Boats', 'Halden', 'Glass Weather', 'Static Hymn',
                                              'Long Exposure', 'Ferry Song', ('Lanterns', [ADA, 'Oren Moss']), 'Coldwater', 'Low Orbit', 'Home Again']),
    dict(artist=ADA, album='Winter Radio', year=2021, date='2021-11-05', art=7, genre='Chamber pop', style='chamber', label='Hollow Pine Records',
         tracks=['Winter Radio', 'Signal Fire', 'Snowmelt', 'The Long Room', 'Tromsø', 'Antenna', 'Quiet Band', 'Kitchen Light', 'Ice Road', 'Last Broadcast']),
    dict(artist=ADA, album='Small Harbours', year=2019, date='2019-05-17', art=2, genre='Indie folk', style='folk', label='Hollow Pine Records',
         tracks=['Small Harbours', 'Salt Wind', 'Breakwater', 'Gulls', 'Tide Tables', 'The Pier at Noon', 'Rope & Anchor', 'Grey Mornings',
                 'Boathouse', 'Fisherman’s Hymn', 'Lights Out']),
    dict(artist=ADA, album='Field Recordings', year=2016, date='2016-09-23', art=3, genre='Ambient', style='ambient', label='Hollow Pine Records',
         tracks=['Rain on Tin', 'Birch', 'Upright Piano', 'Attic', 'Distant Train', 'Moss', 'Ferry Horn', 'Evening Field', 'Tape Hiss']),
    dict(artist=ADA, album='Glass Weather', year=2024, date='2024-01-19', art=4, genre='Indie folk', style='folk', label='Hollow Pine Records',
         kind='single', tracks=['Glass Weather', 'Glass Weather (Piano Version)']),
    dict(artist=ADA, album='Halden', year=2022, date='2022-10-14', art=1, genre='Indie folk', style='chamber', label='Hollow Pine Records',
         kind='ep', tracks=['Halden', 'Orchard Song', 'Thaw', 'Small Lights']),
    dict(artist='Lumen Fields', album='Blue Hour', year=2026, date='2026-02-06', art=4, genre='Electronic', style='electronic', label='Night Signal',
         tracks=['Blue Hour', 'Neon Tide', 'Afterglow', 'Night Bus', 'Satellites', 'Soft Focus', 'Dawn Chorus']),
    dict(artist='The Quiet Engine', album='Copper Line', year=2025, date='2025-04-11', art=6, genre='Jazz', style='jazz', label='Brass Lantern',
         tracks=['Copper Line', 'Brass Weather', 'Slow Train', 'Third Shift', 'Lamplighter', 'Rivets', 'Last Call']),
    dict(artist='Harbor Lights', album='Harbor Lights', year=2025, date='2025-06-20', art=7, genre='Indie folk', style='folk', label='Hollow Pine Records',
         tracks=['Harbor Lights', 'Lighthouse Keeper', 'Sea Glass', 'Coastline', 'Foghorn', 'North Pier']),
    dict(artist='Kestrel Choir', album='Small Hours', year=2024, date='2024-11-29', art=5, genre='Classical', style='classical', label='Vesper Hall',
         tracks=['Small Hours', 'Vespers', 'Lullaby for Kestrels', 'Evensong', 'Matins', 'Nocturne']),
    dict(artist='Oren Moss', album='Undertow', year=2024, date='2024-08-30', art=2, genre='Ambient', style='ambient', label='Night Signal',
         tracks=['Undertow', 'Drift', 'Deep Water', 'Kelp Forest', 'Pressure', 'Sounding', 'Surface']),
    dict(artist='Oren Moss', album='Night Ferry (Original Score)', year=2025, date='2025-10-03', art=6, genre='Soundtracks', style='soundtrack',
         label='Hollow Pine Records', tracks=['Main Title', 'Departure', 'The Ferry at Night', 'Cabin 12', ('Lanterns on the Water', ['Oren Moss', ADA]),
                                              'Fog Bank', 'Arrival', 'End Credits']),
    dict(artist='Mira Sol', album='Salt Hymns', year=2022, date='2022-03-25', art=12, genre='Chamber pop', style='chamber', label='Vesper Hall',
         tracks=['Salt Hymn', 'Sea Psalm', 'Brine', 'White Sails', 'Coastal Prayer', 'Low Light']),
    dict(artist='Various Artists', album='Static Bloom', year=2023, date='2023-07-07', art=8, genre='Electronic', style='electronic', label='Night Signal',
         compilation=True, tracks=[('Static Bloom', ['Lumen Fields']), ('Copper Wire', ['The Quiet Engine']), ('Blue Static', ['Harbor Lights']),
                                   ('Night Signal', ['Oren Moss']), ('Bloom', ['Mira Sol']), ('Interference', ['Kestrel Choir']),
                                   ('Afterimage', ['Lumen Fields']), ('Radio Garden', ['Harbor Lights'])]),
]
ARTISTS = {  # artist.nfo: biography, genres, where from
    ADA: ('Ada Vale writes quiet, wide-open folk records on an old upright piano and a radio she found in her grandmother’s attic. '
          'Her fourth album, Northline, was recorded over one winter in a cabin north of Tromsø.', ['Indie folk', 'Chamber pop', 'Ambient'], 'Oslo, Norway'),
    'Lumen Fields': ('Lumen Fields is a two-person electronic project from Gothenburg: slow synthesisers, field recordings and a drum machine '
                     'that is never quite in time.', ['Electronic', 'Ambient'], 'Gothenburg, Sweden'),
    'The Quiet Engine': ('A five-piece jazz band that started as a late-night residency in a railway bar and never stopped.', ['Jazz'], 'Glasgow, Scotland'),
    'Harbor Lights': ('Harbor Lights make warm, harmony-heavy folk songs about the coast they grew up on.', ['Indie folk'], 'Halifax, Canada'),
    'Kestrel Choir': ('A chamber choir of sixteen voices, singing new music and old hymns in small churches.', ['Classical'], 'Bergen, Norway'),
    'Oren Moss': ('Composer and producer. Oren Moss writes slow, deep ambient records and scores for film and television, and often '
                  'sings with Ada Vale.', ['Ambient', 'Soundtracks'], 'Copenhagen, Denmark'),
    'Mira Sol': ('Mira Sol sings hymns for people who don’t go to church, with a string quartet and a harmonium.', ['Chamber pop'], 'Lisbon, Portugal'),
}
NORTHLINE_LYRICS = ['We left the harbour lights behind', 'and followed the road past the pines', 'and every mile the radio',
                    'kept singing things we didn’t know', 'Northline, northline', 'carry me over the snow', 'the further we go',
                    'the less I want to go home']
GLASS_WEATHER_LYRICS = """The window's full of glass weather
and the kettle's singing low
you said we'd wait it out together
I said I already know

Every pane a little river
every river finds the sea
if the weather's made of glass, love
it can break for you and me"""


def make_music():
    root = MEDIA / 'Music'
    for a in MUSIC:
        artist, album, year = a['artist'], a['album'], a['year']
        folder = root / artist / f'{album} ({year})'
        folder.mkdir(parents=True, exist_ok=True)
        cover = folder / 'cover.jpg'
        if not cover.exists():
            jpeg(square_art(a['art'], album.replace(' (Original Score)', '')), cover)
        nfo = folder / 'album.nfo'
        if not nfo.exists():
            tags = ''.join(f'  <tag>{t}</tag>\n' for t in ([a['kind']] if a.get('kind') else []) + (['compilation'] if a.get('compilation') else []))
            nfo.write_text(f'<?xml version="1.0" encoding="utf-8" standalone="yes"?>\n<album>\n  <title>{xml(album)}</title>\n'
                           f'  <year>{year}</year>\n  <releasedate>{a["date"]}</releasedate>\n  <premiered>{a["date"]}</premiered>\n'
                           f'  <studio>{xml(a["label"])}</studio>\n  <genre>{xml(a["genre"])}</genre>\n{tags}</album>\n')
        fmt = a.get('fmt', 'mp3')
        sr = 96000 if fmt == 'flac' else 44100
        for n, t in enumerate(a['tracks'], 1):
            title, artists = (t if isinstance(t, tuple) else (t, [artist]))
            name = f'{n:02d} - {title.replace("/", "-")}'
            dst = folder / f'{name}.{fmt}'
            seed = seed_of(artist, album, title)
            secs = 48 if title == 'Northline' else 20 + seed % 21
            if title == 'Northline' and not (folder / f'{name}.lrc').exists():
                at = [4.0 + i * 5.0 for i in range(len(NORTHLINE_LYRICS))]
                lrc = f'[ar:{artist}]\n[al:{album}]\n[ti:{title}]\n[length:00:{secs:02d}]\n' + ''.join(
                    f'[{int(s // 60):02d}:{s % 60:05.2f}]{line}\n' for s, line in zip(at, NORTHLINE_LYRICS))
                (folder / f'{name}.lrc').write_text(lrc)
            if title == 'Glass Weather' and album == 'Northline' and not (folder / f'{name}.txt').exists():
                (folder / f'{name}.txt').write_text(GLASS_WEATHER_LYRICS + '\n')
            if dst.exists():
                continue
            wav = WORK / f'{seed:08x}.wav'
            write_wav(wav, song(secs, sr, a['style'], seed), sr)
            tags = {'title': title, 'artist': '; '.join(artists), 'album_artist': artist, 'album': album, 'track': f'{n}/{len(a["tracks"])}',
                    'disc': '1/1', 'date': a['date'], 'genre': a['genre'], 'publisher': a['label'],
                    'composer': 'Oren Moss' if artist == 'Oren Moss' else ('; '.join(artists) if artist != 'Various Artists' else artists[0]),
                    'compilation': '1' if a.get('compilation') else None, 'comment': 'Generated test audio for Seedy (CC0)'}
            if fmt == 'flac':
                tags.update({'LABEL': a['label'], 'RELEASETYPE': a.get('kind', 'album')})
            else:
                tags['MusicBrainz Album Type'] = a.get('kind', 'album')
            job(dst, wav, cover, fmt, tags)
    for artist, (bio, genres, where) in ARTISTS.items():
        nfo = root / artist / 'artist.nfo'
        if (root / artist).exists() and not nfo.exists():
            g = ''.join(f'  <genre>{xml(x)}</genre>\n' for x in genres)
            nfo.write_text(f'<?xml version="1.0" encoding="utf-8" standalone="yes"?>\n<artist>\n  <name>{xml(artist)}</name>\n'
                           f'  <biography>{xml(bio)}</biography>\n{g}  <country>{xml(where)}</country>\n</artist>\n')


# ---------------------------------------------------------------------------------------------------------------------
# Book text: sentences recombined per book, so chapters read like prose and run to a realistic length.

NOVEL = [
    'The {season} light came in low over {place}, and {hero} watched it climb the far wall one board at a time.',
    '{hero} had been told never to trust a quiet morning, and this one was very quiet.',
    'Down by the water the boats knocked together like people who had run out of things to say.',
    'Nobody in {place} said it out loud, but everyone had counted the days.',
    'The wind came off the hill smelling of snow and woodsmoke, and of something older than both.',
    '{friend} was already at the gate, stamping {friend_pos} feet against the cold.',
    'There was a way of walking through {place} after dark that you only learned by getting lost in it.',
    '{hero} kept {thing} close, the way you keep a promise you are not sure you can keep.',
    'The kitchen was warm and smelled of bread, and for a while nobody mentioned what had happened.',
    'It had rained in the night, and every puddle held a small grey piece of the sky.',
    'By noon the fog had lifted enough to show the far shore, and then it came down again as if it had changed its mind.',
    '{elder} had a saying for days like this one, and like most of {elder_pos} sayings it was not very comforting.',
    'Somewhere a door banged twice and then was still.',
    '{hero} thought about turning back, and then thought about what {friend} would say, and kept going.',
    'The road out of {place} ran straight for a mile and then gave up and followed the river.',
    'They ate standing up, the way you do when you are not sure how long you can stay.',
    'The lamp threw their shadows huge against the wall, two giants leaning over a very small table.',
    'Every house in {place} had a window that faced the hill, and in every window there was a light.',
    'It was the kind of silence that has a shape, and {hero} could feel the edges of it.',
    'Later, {hero} would remember that evening as the last ordinary one, though nothing about it felt ordinary at the time.',
    'The ledger of the year was kept in a careful hand, and {hero} turned its pages without reading them.',
    'Frost had drawn ferns on the inside of the glass, and {hero} traced one with a fingertip until it melted.',
    'Out on the hill the trees stood in their rows like a congregation waiting for the sermon to begin.',
    'The bell at the end of the pier rang once, though there was no wind to ring it.',
    '{friend} laughed, a short surprised sound, and then looked away as if it had escaped without permission.',
    'Nothing was missing, and yet the room felt like a sentence with a word taken out.',
    'They walked the rest of the way without talking, and the snow did all the talking for them.',
    'The map was older than the town, and in places it disagreed with it.',
    'In the morning there were footprints in the yard that went in and did not come out.',
    '{hero} slept badly and woke early, and lay listening to the house settle around the cold.',
    'The stove ticked as it cooled, counting down to something only it could see.',
    'There is a kind of tiredness that sleep does not fix, and {hero} had it in both hands.',
    'At the edge of {place} the lights gave out, and after that there was only the road and the sound of their boots.',
    '{elder} kept the old ways because nobody had found better ones, not because they were good.',
    'The letter was short, and {hero} read it three times hoping it would get longer.',
    'All that winter the river ran black under its ice, and nobody went near it.',
    'Her hands were steady, which surprised her more than anything else that night.',
    'It was the sort of question that only has wrong answers, so {friend} did not answer it.',
    'The church clock was eleven minutes slow, and the whole of {place} had agreed to live by it anyway.',
    'When the wind dropped they could hear the sea, a long way off, breathing in its sleep.',
    'The stairs complained at every step, as if they had opinions about where {hero} was going.',
    '{thing} sat on the table between them, and neither of them wanted to be the first to touch it.',
    'There were two kinds of people in {place}: those who stayed, and those who talked about leaving.',
    'Snow began again a little after midnight, slow and enormous, filling in every track they had made.',
    'In the end it was the smallest things {hero} remembered: a cup left on a step, a coat on the wrong hook.',
    'The ice on the water butt was thick enough to stand on, and {hero} did, just to know that it would hold.',
    'Someone had swept the step and left the broom against the door, which in {place} counted as a message.',
    'There was bread, and cheese, and a jar of something {elder} refused to name, and they ate all of it.',
    'The path was older than the wall beside it, and it went wherever it liked.',
    'By the time they reached the top of the hill the town below had become a handful of lights and a church bell.',
    '{friend} said nothing for a long time, and then said the one thing {hero} had hoped not to hear.',
    'A cart went by with its lamps lit, though it was only four in the afternoon.',
    'The wind changed in the night, and in the morning everything smelled of the sea.',
    'It was not the first time {hero} had lied to {friend}, but it was the first time it had felt like stealing.',
    'Every winter the same three families argued about the same fence, and every spring they mended it together.',
    'The clock in the hall had stopped at ten past two, and nobody could remember which day.',
    '{hero} wrote it all down that night, every word, so that later nobody could tell it differently.',
    'Rain came in sideways off the water and found every gap in every coat.',
    'The cellar smelled of apples and cold stone and, faintly, of smoke.',
    'Out past the last house the snow lay unbroken, blue in the half-light, waiting for someone to be the first.',
]
DIALOGUE = [
    ('“You should have woken me,”', 'said', 'There was no anger in it, only a kind of tired worry.'),
    ('“It wasn’t here yesterday,”', 'said', 'Neither of them moved.'),
    ('“We could just leave it,”', 'said', 'It was the kind of thing people say so that someone can tell them no.'),
    ('“Do you hear that?”', 'asked', 'They both listened, and there was nothing, and then there was.'),
    ('“Count them again,”', 'said', 'So they counted again, and the number did not change.'),
    ('“Nobody has to know,”', 'said', 'Everybody would know by morning. That was how it worked here.'),
    ('“I’m not afraid of it,”', 'said', 'It was very nearly true.'),
    ('“Tell me what you saw,”', 'said', 'And so the whole thing came out, in the wrong order, all at once.'),
    ('“The road will be closed by tonight,”', 'said', 'It was said the way you mention the weather, which is to say carefully.'),
    ('“Then we go now,”', 'said', 'There was no arguing with that voice, and nobody tried.'),
    ('“Some things are only true in winter,”', 'said', 'The fire cracked, as if in agreement.'),
    ('“You’ve got your mother’s stubbornness,”', 'said', 'It was meant as a complaint, and taken as a compliment.'),
]
ESSAY = [
    'I have lived near {place} for most of my life, and I still could not draw you a map of it.',
    'There is a particular grey that only belongs to the coast in {season}, and I have spent years trying to name it.',
    'The first thing you notice is the quiet; the second is that it is not quiet at all.',
    'Most of what I know about {thing} I learned by getting it wrong in front of someone patient.',
    'People ask me why I keep coming back, and the honest answer is that I never really left.',
    'The old harbour master kept a notebook of every ship that came in, and nothing else.',
    'In the mornings the light arrives sideways, and everything it touches looks briefly important.',
    'A good field guide tells you what to look for; a better one tells you how to wait.',
    'I once spent a whole afternoon watching the tide come in, and I would do it again.',
    'If you stand still long enough, the birds stop treating you as weather and start treating you as furniture.',
    'There are places that ask nothing of you, and they are rarer than you would think.',
    'The ferry runs twice a day in summer, once in winter, and not at all when the sea has opinions.',
    'Every town I love has one café that is open too early and closes too soon.',
    'My grandmother read the sky the way other people read the newspaper: daily, sceptically, and out loud.',
    'Weather is the only conversation that everyone in {place} is qualified to have.',
    'I walked out along the breakwater, and the wind took my hat and my opinions at the same time.',
    'What I want to say about {thing} is simple, and it has taken me twenty years to find the words.',
    'You can tell the time here by the light on the water, if you are patient and not in a hurry.',
    'The fog does not arrive so much as it is suddenly discovered, already everywhere.',
    'I kept a list that year of every small kindness, and it was longer than I expected.',
    'Leaving is a skill, like knots or bread, and I have never been good at it.',
    'The best maps leave room in the margins for the things you will find yourself.',
    'I went back in the spring, expecting it to have changed, and it had, though not in any way I could point to.',
    'There is a bench at the end of the harbour wall that faces the wrong way, and it is my favourite place to sit.',
    'Nobody tells you how loud the sea is at night when you are trying to sleep beside it.',
    'The light that afternoon was the colour of weak tea, and everything looked faintly historical.',
    'I have learned to trust the people who check the sky before they check their watch.',
    'Some days the whole coast smells of diesel and seaweed, and I would not change it for anything.',
    'The tide tables were pinned up in the café, curling at the corners, consulted by everyone.',
    'It took me a long time to understand that the quiet here is not empty, only patient.',
]
CHAPTER_WORDS = ['Thaw', 'Low Water', 'The Ferry', 'Salt', 'Night Shift', 'The Pier', 'Signal', 'The Letter', 'Small Hours', 'The Map',
                 'Iron Gate', 'The Well', 'Ash', 'The Crossing', 'Harbour', 'The Visitor', 'Lamplight', 'Ice', 'The Long Road', 'Fieldwork',
                 'North Wind', 'The Garden', 'Ledger', 'Stillwater', 'The Bell', 'Return', 'Tidewater', 'Sleet', 'The Cellar', 'Morning',
                 'Weather', 'The Bridge', 'Embers', 'The Station', 'Driftwood', 'Black Ice', 'The Archive', 'Last Light', 'Undertow', 'Home']
GLASS_CHAPTERS = ['The Count', 'Rows', 'The Ledger', 'Ilse', 'Sleet', 'The New Tree', 'Market Day', 'Lanterns', 'The Glazier', 'Thin Ice',
                  'What the Ledger Said', 'Frost', 'The Missing Page', 'Bells', 'The Mayor’s House', 'Cold Light', 'Snowblind', 'The Well',
                  'Old Pear', 'Breaking', 'The Second Count', 'Smoke', 'Hollow Pine', 'The Long Night', 'Glasswork', 'Thaw Wind', 'The Last Row',
                  'Spring']
FROST_OPENING = [
    'The frost came early that year, the way it always did in the stories, quietly and all at once. Ilse woke to find the orchard silvered, '
    'every branch drawn in white, and the glass fruit ringing faintly whenever the wind found its way between the rows.',
    'She dressed in the dark and went out with the lantern, counting trees the way her father had taught her: by touch first, then by sound, '
    'and only last by sight. Forty-one, forty-two. The ledger in her coat pocket was cold enough to hurt.',
    'At the end of the eleventh row she stopped. There should have been a gap there, a stump where lightning had taken the old pear two summers '
    'ago. Instead there was a tree, young and straight, its branches heavy with fruit so clear she could see the lantern burning through it.',
    '“You weren’t here,” she said, and felt foolish, and said it again anyway. The tree did what trees do. Somewhere behind her, down in the town, '
    'a dog began to bark and then thought better of it.']
FOG_CHAPTERS = ['Arrival', 'The Weather Station', 'Sea Fret', 'Haar', 'Counting Foghorns', 'A Grey Map', 'The Lighthouse', 'Advection',
                'Burning Off', 'The Long Coast', 'Salt Marsh', 'Fog Signals', 'The Cold Front', 'Inversion', 'Radiation Fog', 'The Harbour Bell',
                'Clearing', 'After the Fog']
CPP = 1600  # characters per page, as Seedy's reader counts them


def prose(rng, cast, chars, essay=False):
    bank = ESSAY if essay else NOVEL
    paras, total, recent = [], 0, []
    cap = lambda x: x[0].upper() + x[1:]
    while total < chars:
        if not essay and rng.random() < 0.22:
            line, verb, after = DIALOGUE[int(rng.integers(len(DIALOGUE)))]
            who = cast['friend'] if rng.random() < 0.6 else cast['elder']
            p = f'{line} {who} {verb}. {after}'
        else:
            sent = []
            for _ in range(int(rng.integers(3, 7))):
                i = int(rng.integers(len(bank)))
                while i in recent:  # no sentence twice within a page or two
                    i = int(rng.integers(len(bank)))
                recent = (recent + [i])[-len(bank) // 2:]
                sent.append(cap(bank[i].format(**cast)))
            p = ' '.join(sent)
        paras.append(cap(p))
        total += len(p)
    return paras


def chapters_for(b):
    """[(label, title, paragraphs)] sized so the book has b['pages'] pages."""
    rng = np.random.default_rng(seed_of(b['author'], b['title']))
    titles = b.get('chapters')
    if not titles:
        n = b.get('n', 14)
        pool = list(CHAPTER_WORDS)
        rng.shuffle(pool)
        titles = pool[:n]
    n = len(titles)
    body_pages = b['pages'] - 2  # cover and title page
    per = b.get('per') or [body_pages // n + (1 if i < body_pages % n else 0) for i in range(n)]
    out = []
    essay = b['kind'] in ('essays', 'guide')
    for i, (t, p) in enumerate(zip(titles, per)):
        label = f'Chapter {i + 1}' if b['kind'] == 'novel' else ('' if b['kind'] == 'stories' else f'{i + 1}')
        target = p * CPP - 500 - len(t) - len(label)
        paras = []
        if b['title'] == 'The Glass Orchard' and t == 'Frost':
            paras = list(FROST_OPENING)
        have = sum(len(x) for x in paras)
        more = prose(rng, b['cast'], max(0, target - 600 - have), essay)
        while more and have + sum(len(x) for x in more) > target:
            more.pop()
        out.append((label, t, paras + more))
    return out


def isbn13(n):
    digits = f'978000{n:06d}'
    check = (10 - sum(int(d) * (1 if i % 2 == 0 else 3) for i, d in enumerate(digits)) % 10) % 10
    return f'978-0-00-{n:06d}-{check}'


GLASS_DESC = ('The orchard at Halden grows glass instead of fruit, and every winter someone has to walk the rows and count it. This year it '
              'is Ilse, who has just found a tree that was not there in the autumn. The third book in the Halden Cycle follows her through one '
              'long frost, a missing ledger, and a town that would rather not know what the new tree means.')
HALDEN_CAST = dict(hero='Ilse', friend='Mats', friend_pos='his', elder='old Brekke', elder_pos='his', place='Halden', thing='the ledger', season='winter')


def cast(hero, friend, friend_pos, elder, elder_pos, place, thing, season):
    return dict(hero=hero, friend=friend, friend_pos=friend_pos, elder=elder, elder_pos=elder_pos, place=place, thing=thing, season=season)


ML = 'Mara Linde'
HC = 'The Halden Cycle'
BOOKS = [
    dict(author=ML, title='Halden', year=2019, date='2019-09-12', art=1, series=HC, index=1, genres=['Fantasy', 'Literary fiction'], pages=300, n=24,
         kind='novel', cast=HALDEN_CAST, rating=8, desc='Every family in Halden keeps a ledger, and every ledger keeps a secret. When Ilse’s grandmother '
         'dies in the first snow, the glass orchard passes to a girl of twelve who has never been allowed to count it. The first book in the Halden Cycle.'),
    dict(author=ML, title='The Long Frost', year=2021, date='2021-10-07', art=4, series=HC, index=2, genres=['Fantasy', 'Literary fiction'], pages=320,
         n=26, kind='novel', cast=HALDEN_CAST, rating=8, desc='The winter that would not end. Ilse is fourteen, the orchard has gone silent, and the '
         'glaziers who come up from the coast every spring have not come. The second book in the Halden Cycle.'),
    dict(author=ML, title='The Glass Orchard', year=2023, date='2023-09-14', art=2, series=HC, index=3, genres=['Fantasy', 'Literary fiction'], pages=412,
         chapters=GLASS_CHAPTERS, kind='novel', cast=HALDEN_CAST, rating=8.6, isbn='978-0-00-000000-0', desc=GLASS_DESC, pdf=True,
         per=[16, 17, 16, 16, 17, 16, 16, 17, 16, 16, 16, 15] + [14] * 8 + [13] * 8),  # 46% = page 190, in chapter 12, as on the boards
    dict(author=ML, title='Lantern Season', year=2025, date='2025-11-20', art=5, series=HC, index=4, genres=['Fantasy', 'Literary fiction'], pages=280,
         n=22, kind='novel', cast=HALDEN_CAST, rating=8, desc='In the weeks before midwinter, Halden hangs a lantern in every window for the ones who '
         'did not come home. This year there is one lantern too many. The fourth book in the Halden Cycle.'),
    dict(author=ML, title='Small Weathers', year=2020, date='2020-04-02', art=3, genres=['Short stories', 'Literary fiction'], pages=180, n=11,
         kind='stories', cast=cast('Nell', 'Aron', 'his', 'the ferryman', 'his', 'the island', 'the radio', 'autumn'), rating=7,
         desc='Eleven stories about small towns and the weather that runs them: a ferry that will not leave, a radio that only plays at night, '
         'a girl who keeps the rain in jars.'),
    dict(author=ML, title='The Map Room', year=2018, date='2018-03-08', art=0, genres=['Literary fiction'], pages=240, n=18, kind='novel',
         cast=cast('Agnes', 'Tomas', 'his', 'her mother', 'her', 'Kessel', 'the unfinished map', 'spring'), rating=7,
         desc='A cartographer’s daughter inherits a tall house with one locked room, and in it every map her mother never finished.'),
    dict(author=ML, title='Salt Year', year=2016, date='2016-06-16', art=7, genres=['Literary fiction'], pages=200, n=12, kind='novel',
         cast=cast('Hedda', 'Sigrid', 'her', 'their father', 'his', 'Saltholm', 'the salt pans', 'summer'), rating=7,
         desc='Mara Linde’s first novel: one year on a salt farm at the edge of the North Sea, told by the youngest of four sisters.'),
    dict(author=ML, title='Notes from Halden', year=2024, date='2024-05-23', art=1, genres=['Essays'], pages=150, n=12, kind='essays',
         cast=cast('I', 'my neighbour', 'her', 'the orchard keeper', 'his', 'Halden', 'orchards', 'winter'), rating=8,
         desc='Essays on the town behind the Halden Cycle: its orchards, winters and ledgers, and the people who still keep them.'),
    dict(author='Jonah Reyes', title='Salt & Signal', year=2026, date='2026-09-03', art=4, genres=['Science fiction'], pages=260, n=20, kind='novel',
         cast=cast('Ren', 'Juno', 'her', 'the old keeper', 'his', 'the drowned coast', 'the receiver', 'autumn'), rating=8,
         desc='A lighthouse keeper on a drowned coast picks up a distress signal from a ship that sank forty years ago, and answers it.'),
    dict(author='Eli Brandt', title='Small Harbours', year=2025, date='2025-03-13', art=1, genres=['Essays', 'Travel'], pages=170, n=14, kind='essays',
         cast=cast('I', 'the harbour master', 'his', 'an old fisherman', 'his', 'the North Sea coast', 'ferries', 'winter'), rating=8,
         desc='Fourteen small harbours around the North Sea, one year of ferries and bad weather, and what the people who stay there know.'),
    dict(author='R. K. Amsel', title='The Quiet Engine', year=2025, date='2025-08-21', art=3, genres=['Thriller', 'Science fiction'], pages=280, n=24,
         kind='novel', cast=cast('Mira', 'the conductor', 'his', 'Dr Hallam', 'her', 'the night train', 'the engine log', 'winter'), rating=7,
         desc='An engineer on a night train across the continent realises that the train has not stopped for six hours, and that nobody else has noticed.'),
    dict(author='Tess Varga', title='Copper Line', year=2024, date='2024-02-29', art=6, genres=['Literary fiction'], pages=250, n=18, kind='novel',
         cast=cast('Zsófi', 'Bence', 'his', 'Grandmother Varga', 'her', 'Rézbánya', 'the copper line', 'summer'), rating=8,
         desc='Three generations of a mining family, one town, and the copper line that runs underneath all of it.'),
    dict(author='Dana Holm', title='North of Nowhere', year=2024, date='2024-10-10', art=0, genres=['Mystery', 'Crime'], pages=240, n=22, kind='novel',
         cast=cast('Inspector Lund', 'Berg', 'his', 'the coroner', 'her', 'Nordvik', 'the case file', 'winter'), rating=7,
         desc='A body in a snowbound cabin three hundred kilometres north of anywhere, and only one road in or out.'),
    dict(author='Ada Sørum', title='Field Notes on Leaving', year=2023, date='2023-04-27', art=2, genres=['Memoir'], pages=190, n=15, kind='essays',
         cast=cast('I', 'my brother', 'his', 'my father', 'his', 'Tromsø', 'leaving', 'spring'), rating=8,
         desc='A memoir in fragments about leaving home, written over the year the author finally did.'),
    dict(author='Paul Imre', title='The Undercroft', year=2023, date='2023-10-26', art=7, genres=['Horror'], pages=230, n=20, kind='novel',
         cast=cast('Teodor', 'Klara', 'her', 'the verger', 'his', 'St Aldric’s', 'the iron key', 'autumn'), rating=7,
         desc='Beneath the old church there is a room that appears on no plan. Teodor has the only key, and something on the other side has started knocking.'),
    dict(author='Dana Holm', title='Coldwater', year=2019, date='2019-01-24', art=0, series='Coldwater Mysteries', index=1, genres=['Mystery', 'Crime'],
         pages=220, n=20, kind='novel', cast=cast('Inspector Lund', 'Berg', 'his', 'the harbour master', 'his', 'Coldwater', 'the logbook', 'winter'),
         rating=8, desc='The first Coldwater mystery: a trawler comes home with its nets full and its crew missing.'),
    dict(author='Dana Holm', title='Low Tide', year=2020, date='2020-06-11', art=3, series='Coldwater Mysteries', index=2, genres=['Mystery', 'Crime'],
         pages=210, n=20, kind='novel', cast=cast('Inspector Lund', 'Berg', 'his', 'the mayor', 'her', 'Coldwater', 'the tide table', 'summer'),
         rating=7, desc='The lowest tide in a century uncovers a car that was reported stolen thirty years ago, with someone still in it.'),
    dict(author='Dana Holm', title='High Water', year=2022, date='2022-02-17', art=6, series='Coldwater Mysteries', index=3, genres=['Mystery', 'Crime'],
         pages=230, n=21, kind='novel', cast=cast('Inspector Lund', 'Berg', 'his', 'the priest', 'his', 'Coldwater', 'the flood map', 'autumn'),
         rating=7, desc='The river is rising, the town is cut off, and the only witness to a murder refuses to leave the church tower.'),
    dict(author='Dana Holm', title='Black Ice', year=2023, date='2023-11-30', art=5, series='Coldwater Mysteries', index=4, genres=['Mystery', 'Crime'],
         pages=220, n=20, kind='novel', cast=cast('Inspector Lund', 'Berg', 'his', 'the doctor', 'her', 'Coldwater', 'the photograph', 'winter'),
         rating=7, desc='One icy night, three accidents on the same bend of the coast road. Lund does not believe in coincidences.'),
    dict(author='Ines Northey', title='A Field Guide to Fog', year=2021, date='2021-10-21', art=6, series='Field Guides', index=1,
         genres=['Nature writing'], pages=160, chapters=FOG_CHAPTERS, kind='guide', rating=9,
         cast=cast('I', 'the lighthouse keeper', 'his', 'the forecaster', 'her', 'the east coast', 'fog', 'autumn'),
         desc='Sea fret, haar, advection and radiation fog: a walk along one long coast in the foggiest year on record, and a guide to reading the grey.'),
    dict(author='Ines Northey', title='A Field Guide to Frost', year=2022, date='2022-11-17', art=1, series='Field Guides', index=2,
         genres=['Nature writing'], pages=150, n=14, kind='guide', rating=8,
         cast=cast('I', 'a farmer', 'his', 'the forecaster', 'her', 'the high moor', 'frost', 'winter'),
         desc='Hoar frost, rime and fern frost on windows: a winter on the high moor, looking very closely at the cold.'),
    dict(author='Ines Northey', title='A Field Guide to Thaw', year=2024, date='2024-03-21', art=3, series='Field Guides', index=3,
         genres=['Nature writing'], pages=150, n=14, kind='guide', rating=8,
         cast=cast('I', 'my daughter', 'her', 'the ferryman', 'his', 'the river valley', 'meltwater', 'spring'),
         desc='The weeks when winter lets go: meltwater, mud, the first birds and the sound of a river waking up.'),
]
PUBLISHERS = {ML: 'Hollow Pine Books', 'Ines Northey': 'Hollow Pine Books'}


def file_as(name):
    parts = name.split()
    return f'{parts[-1]}, {" ".join(parts[:-1])}' if len(parts) > 1 else name


def book_meta(b, isbn):
    subjects = ''.join(f'    <dc:subject>{xml(g)}</dc:subject>\n' for g in b['genres'])
    series = (f'    <meta name="calibre:series" content="{xml(b["series"])}"/>\n    <meta name="calibre:series_index" content="{b["index"]}"/>\n'
              if b.get('series') else '')
    return (f'    <dc:identifier opf:scheme="ISBN">{isbn}</dc:identifier>\n'
            f'    <dc:title id="title">{xml(b["title"])}</dc:title>\n    <meta refines="#title" property="title-type">main</meta>\n'
            f'    <dc:creator id="author" opf:role="aut" opf:file-as="{xml(file_as(b["author"]))}">{xml(b["author"])}</dc:creator>\n'
            f'    <dc:language>en</dc:language>\n    <dc:publisher>{xml(PUBLISHERS.get(b["author"], "Hollow Pine Books"))}</dc:publisher>\n'
            f'    <dc:date>{b["date"]}</dc:date>\n    <dc:description>{xml(b["desc"])}</dc:description>\n{subjects}'
            f'    <dc:rights>Generated test content for Seedy (CC0)</dc:rights>\n{series}'
            f'    <meta name="calibre:rating" content="{b["rating"]}"/>\n')


CSS = ('body { font-family: serif; line-height: 1.5; margin: 0 5%; }\nh1 { font-size: 1.8em; margin: 0.2em 0 1em; }\n'
       '.num { font-family: sans-serif; font-size: 0.8em; letter-spacing: 0.12em; text-transform: uppercase; margin: 3em 0 0; }\n'
       'p { margin: 0; text-indent: 1.5em; text-align: justify; }\np.first, h1 + p { text-indent: 0; }\n'
       '.title { text-align: center; margin-top: 30%; }\n.title p { text-indent: 0; text-align: center; }\n'
       '.cover { margin: 0; padding: 0; text-align: center; }\n.cover img { max-width: 100%; max-height: 100%; }\n')


def xhtml(title, body):
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" '
            'xmlns:epub="http://www.idpf.org/2007/ops" lang="en" xml:lang="en">\n'
            f'<head><meta charset="UTF-8"/><title>{xml(title)}</title><link rel="stylesheet" type="text/css" href="../style.css"/></head>\n'
            f'<body>\n{body}\n</body>\n</html>\n')


def write_epub(path, b, chs, cover_jpg, isbn):
    uid = uuid.uuid5(uuid.NAMESPACE_URL, 'seedy-test-book:' + b['author'] + '/' + b['title'])
    tmp = path.with_suffix('.part')
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr(zipfile.ZipInfo('mimetype'), 'application/epub+zip', compress_type=zipfile.ZIP_STORED)
        z.writestr('META-INF/container.xml', '<?xml version="1.0" encoding="UTF-8"?>\n<container version="1.0" '
                   'xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n  <rootfiles>\n    <rootfile full-path="OEBPS/content.opf" '
                   'media-type="application/oebps-package+xml"/>\n  </rootfiles>\n</container>\n')
        z.writestr('OEBPS/style.css', CSS)
        z.writestr('OEBPS/images/cover.jpg', cover_jpg)
        z.writestr('OEBPS/text/cover.xhtml', xhtml(b['title'], f'<div class="cover"><img src="../images/cover.jpg" alt="{xml(b["title"])}"/></div>'))
        pub = PUBLISHERS.get(b['author'], 'Hollow Pine Books')
        z.writestr('OEBPS/text/title.xhtml', xhtml(b['title'], f'<section class="title" epub:type="titlepage"><h1>{xml(b["title"])}</h1>'
                   f'<p>{xml(b["author"])}</p><p>{xml(pub)}, {b["year"]}</p><p>ISBN {isbn}</p>'
                   '<p>This book was generated for testing Seedy. Its text is made up and dedicated to the public domain (CC0).</p></section>'))
        items, spine, nav, ncx = [], [], [], []
        for i, (label, title, paras) in enumerate(chs, 1):
            name = f'ch{i:02d}.xhtml'
            head = (f'<p class="num">{xml(label)}</p>' if label else '') + f'<h1>{xml(title)}</h1>'
            body = '\n'.join(f'<p>{xml(p)}</p>' for p in paras)
            z.writestr(f'OEBPS/text/{name}', xhtml(title, f'<section epub:type="chapter">{head}\n{body}</section>'))
            items.append(f'    <item id="ch{i:02d}" href="text/{name}" media-type="application/xhtml+xml"/>')
            spine.append(f'    <itemref idref="ch{i:02d}"/>')
            nav.append(f'      <li><a href="text/{name}">{xml(title)}</a></li>')
            ncx.append(f'    <navPoint id="np{i}" playOrder="{i}"><navLabel><text>{xml(title)}</text></navLabel><content src="text/{name}"/></navPoint>')
        z.writestr('OEBPS/nav.xhtml', '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" '
                   'xmlns:epub="http://www.idpf.org/2007/ops" lang="en" xml:lang="en">\n<head><meta charset="UTF-8"/><title>Contents</title></head>\n'
                   '<body>\n  <nav epub:type="toc" id="toc">\n    <h1>Contents</h1>\n    <ol>\n' + '\n'.join(nav) + '\n    </ol>\n  </nav>\n'
                   '  <nav epub:type="landmarks" hidden="">\n    <ol>\n      <li><a epub:type="cover" href="text/cover.xhtml">Cover</a></li>\n'
                   '      <li><a epub:type="bodymatter" href="text/ch01.xhtml">Start</a></li>\n    </ol>\n  </nav>\n</body>\n</html>\n')
        z.writestr('OEBPS/toc.ncx', '<?xml version="1.0" encoding="UTF-8"?>\n<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">\n'
                   f'  <head><meta name="dtb:uid" content="urn:uuid:{uid}"/></head>\n  <docTitle><text>{xml(b["title"])}</text></docTitle>\n'
                   '  <navMap>\n' + '\n'.join(ncx) + '\n  </navMap>\n</ncx>\n')
        z.writestr('OEBPS/content.opf', '<?xml version="1.0" encoding="UTF-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" '
                   'unique-identifier="uid" xml:lang="en">\n  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">\n'
                   f'    <dc:identifier id="uid">urn:uuid:{uid}</dc:identifier>\n' + book_meta(b, isbn) +
                   '    <meta name="cover" content="cover-image"/>\n    <meta property="dcterms:modified">2026-10-01T00:00:00Z</meta>\n  </metadata>\n'
                   '  <manifest>\n    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>\n'
                   '    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>\n    <item id="css" href="style.css" media-type="text/css"/>\n'
                   '    <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>\n'
                   '    <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>\n'
                   '    <item id="titlepage" href="text/title.xhtml" media-type="application/xhtml+xml"/>\n' + '\n'.join(items) + '\n  </manifest>\n'
                   '  <spine toc="ncx">\n    <itemref idref="cover" linear="no"/>\n    <itemref idref="titlepage"/>\n' + '\n'.join(spine) +
                   '\n  </spine>\n</package>\n')
    tmp.rename(path)


# A small PDF writer: 6 x 9 in pages of Times-Roman text, the cover as the first page.
TIMES = {' ': 250, '!': 333, '"': 408, "'": 180, '(': 333, ')': 333, ',': 250, '-': 333, '.': 250, ':': 278, ';': 278, '?': 444, '&': 778,
         'A': 722, 'B': 667, 'C': 667, 'D': 722, 'E': 611, 'F': 556, 'G': 722, 'H': 722, 'I': 333, 'J': 389, 'K': 722, 'L': 611, 'M': 889,
         'N': 722, 'O': 722, 'P': 556, 'Q': 722, 'R': 667, 'S': 556, 'T': 611, 'U': 722, 'V': 722, 'W': 944, 'X': 722, 'Y': 722, 'Z': 611,
         'a': 444, 'b': 500, 'c': 444, 'd': 500, 'e': 444, 'f': 333, 'g': 500, 'h': 500, 'i': 278, 'j': 278, 'k': 500, 'l': 278, 'm': 778,
         'n': 500, 'o': 500, 'p': 500, 'q': 500, 'r': 333, 's': 389, 't': 278, 'u': 500, 'v': 500, 'w': 722, 'x': 500, 'y': 500, 'z': 444,
         '’': 333, '‘': 333, '“': 444, '”': 444, '–': 500, '—': 1000}


def pdf_str(s):
    return '(' + s.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)') + ')'


def write_pdf(path, b, chs, cover):
    W, H, M, size, lead = 432, 648, 54, 10.5, 14.5
    width = W - 2 * M
    lines_per_page = int((H - 2 * M - 20) / lead)
    pages = []  # each: list of content-stream lines

    def measure(s, sz):
        return sum(TIMES.get(c, 500) for c in s) * sz / 1000

    def text_lines(p, indent):
        out, line, first = [], '', True
        for word in p.split():
            trial = (line + ' ' + word).strip()
            if line and measure(trial, size) > width - (indent if first else 0):
                out.append((line, indent if first else 0))
                line, first = word, False
            else:
                line = trial
        return out + [(line, indent if first else 0)]

    title_page = [f'BT /F2 22 Tf {M} {H - 200} Td {pdf_str(b["title"])} Tj ET', f'BT /F1 13 Tf {M} {H - 230} Td {pdf_str(b["author"])} Tj ET',
                  f'BT /F1 10 Tf {M} {M + 30} Td {pdf_str("Hollow Pine Books · print edition · generated test content (CC0)".replace("·", "-"))} Tj ET']
    pages.append(title_page)
    for label, title, paras in chs:
        cur, y = [], H - M - 60
        if label:
            cur.append(f'BT /F1 9 Tf {M} {y} Td {pdf_str(label.upper())} Tj ET')
        y -= 30
        cur.append(f'BT /F2 20 Tf {M} {y} Td {pdf_str(title)} Tj ET')
        y -= 34
        for pi, p in enumerate(paras):
            for line, ind in text_lines(p, 0 if pi == 0 else 18):
                if y < M + 20:
                    pages.append(cur)
                    cur, y = [], H - M
                cur.append(f'BT /F1 {size} Tf {M + ind} {y:.1f} Td {pdf_str(line)} Tj ET')
                y -= lead
        pages.append(cur)
    objs = []

    def obj(body):
        objs.append(body)
        return len(objs)

    f1 = obj(b'<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>')
    f2 = obj(b'<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>')
    img = obj(b'<< /Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length %d >>\nstream\n'
              % (cover.width, cover.height, len(jb := jpeg_bytes(cover, 85))) + jb + b'\nendstream')
    pages_id = len(objs) + 1
    objs.append(None)  # the page tree, filled in below
    kids = []
    all_pages = [[f'q {W} 0 0 {H} 0 0 cm /Im1 Do Q']] + pages
    for n, content in enumerate(all_pages):
        if n > 1:
            content = content + [f'BT /F1 8 Tf {W / 2 - 6} {M / 2} Td {pdf_str(str(n - 1))} Tj ET']
        data = zlib.compress('\n'.join(content).encode('cp1252', 'replace'))
        c = obj(b'<< /Length %d /Filter /FlateDecode >>\nstream\n' % len(data) + data + b'\nendstream')
        kids.append(obj(f'<< /Type /Page /Parent {pages_id} 0 R /MediaBox [0 0 {W} {H}] /Contents {c} 0 R '
                        f'/Resources << /Font << /F1 {f1} 0 R /F2 {f2} 0 R >> /XObject << /Im1 {img} 0 R >> >> >>'.encode()))
    objs[pages_id - 1] = f'<< /Type /Pages /Kids [{" ".join(f"{k} 0 R" for k in kids)}] /Count {len(kids)} >>'.encode()
    info = obj(f'<< /Title {pdf_str(b["title"])} /Author {pdf_str(b["author"])} /Subject {pdf_str("Print edition")} '
               f'/Creator (Seedy test media) /Producer (make-music-books.py) >>'.encode('cp1252'))
    cat = obj(f'<< /Type /Catalog /Pages {pages_id} 0 R >>'.encode())
    out = bytearray(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
    offsets = []
    for i, body in enumerate(objs, 1):
        offsets.append(len(out))
        out += f'{i} 0 obj\n'.encode() + body + b'\nendobj\n'
    xref = len(out)
    out += f'xref\n0 {len(objs) + 1}\n0000000000 65535 f \n'.encode() + b''.join(f'{o:010d} 00000 n \n'.encode() for o in offsets)
    out += f'trailer\n<< /Size {len(objs) + 1} /Root {cat} 0 R /Info {info} 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
    path.write_bytes(bytes(out))
    return len(all_pages)


def make_books():
    root = MEDIA / 'Books'
    for n, b in enumerate(BOOKS):
        folder = root / b['author']
        folder.mkdir(parents=True, exist_ok=True)
        dst = folder / f'{b["title"]}.epub'
        isbn = b.get('isbn') or isbn13(100 + n)
        need_pdf = b.get('pdf') and not (folder / f'{b["title"]}.pdf').exists()
        if dst.exists() and not need_pdf:
            continue
        cover = book_art(b['art'], b['title'], b['author'])
        chs = chapters_for(b)
        if not dst.exists():
            write_epub(dst, b, chs, jpeg_bytes(cover), isbn)
        if need_pdf:
            # The print edition: its metadata comes from an .opf next to it, its cover from a .jpg of the same name.
            write_pdf(folder / f'{b["title"]}.pdf', b, chs, cover)
            jpeg(cover, folder / f'{b["title"]}.jpg')
            (folder / f'{b["title"]}.opf').write_text(
                '<?xml version="1.0" encoding="UTF-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">\n'
                '  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">\n'
                f'    <dc:identifier id="uid">{isbn}</dc:identifier>\n' + book_meta(b, isbn) + '  </metadata>\n</package>\n')


# ---------------------------------------------------------------------------------------------------------------------
# Audiobooks: one M4B per book with chapters; the author is the album artist, the narrator the composer.

FOG_SECS = [72, 66, 80, 69, 62, 76, 71, 30, 27, 29, 26, 25, 29, 28, 27, 29, 30, 29]  # 58% lands in chapter 7, as on the boards
AUDIOBOOKS = [
    dict(author='Ines Northey', title='A Field Guide to Fog', narrator='Sam Okafor', f0=112, year=2021, art=7, genre='Nature writing',
         chapters=list(zip(FOG_CHAPTERS, FOG_SECS))),
    dict(author=ML, title='The Glass Orchard', narrator='Sam Okafor', f0=112, year=2023, art=2, genre='Fantasy', chapters=[(c, 14) for c in GLASS_CHAPTERS]),
    dict(author='Tess Varga', title='Copper Line', narrator='Nadia Brooks', f0=205, year=2024, art=6, genre='Literary fiction', n=10),
    dict(author='Jonah Reyes', title='Salt & Signal', narrator='Theo Marsh', f0=125, year=2026, art=4, genre='Science fiction', n=10),
    dict(author='Eli Brandt', title='Small Harbours', narrator='Eli Brandt', f0=104, year=2025, art=3, genre='Essays', n=8),
    dict(author='Ruth Calder', title='Night Ferry', narrator='Imogen Lark', f0=212, year=2025, art=1, genre='Thriller', n=12,
         desc='A night crossing, a cabin that is not on the deck plan, and a passenger who insists the ferry sank in 1987. The novel behind the film.'),
    dict(author='Paul Imre', title='The Undercroft', narrator='Daniel Oyelaran', f0=98, year=2023, art=5, genre='Horror', n=10),
    # One MP3 per chapter, as many rips come: Seedy plays the folder as one book.
    dict(author='Dana Holm', title='North of Nowhere', narrator='Imogen Lark', f0=190, year=2024, art=0, genre='Mystery', n=8, files='mp3'),
]


def make_audiobooks():
    root = MEDIA / 'Audiobooks'
    sr = 22050
    for ab in AUDIOBOOKS:
        folder = root / ab['author'] / ab['title']
        dst = folder / f'{ab["title"]}.m4b'
        folder.mkdir(parents=True, exist_ok=True)
        cover = folder / 'cover.jpg'
        if not cover.exists():
            jpeg(square_art(ab['art'], ab['title']), cover)
        if dst.exists():
            continue
        book = next((b for b in BOOKS if b['title'] == ab['title'] and b['author'] == ab['author']), None)
        chapters = ab.get('chapters')
        if not chapters:
            titles = [t for _, t, _ in chapters_for(book)] if book else CHAPTER_WORDS[:ab['n']]
            rng = np.random.default_rng(seed_of(ab['title']))
            chapters = [(t, int(rng.integers(20, 34))) for t in titles[:ab['n']]]
        seed = seed_of(ab['author'], ab['title'], 'audio')
        if ab.get('files') == 'mp3':
            for i, (t, secs) in enumerate(chapters):
                part = folder / f'{i + 1:02d} - {t.replace("/", "-")}.mp3'
                if part.exists():
                    continue
                x = narration(secs, sr, seed + i, ab['f0'])
                chime = bell(midi(76), 2.5, sr) * 0.12 + bell(midi(83), 2.5, sr) * 0.08
                x[:len(chime)] += chime[:len(x)]
                wav = WORK / f'ab{seed:08x}-{i:02d}.wav'
                write_wav(wav, x, sr)
                job(part, wav, cover, 'mp3', {'title': t, 'album': ab['title'], 'artist': ab['author'], 'album_artist': ab['author'],
                                              'composer': ab['narrator'], 'comment': f'Read by {ab["narrator"]}', 'genre': ab['genre'],
                                              'date': str(ab['year']), 'track': f'{i + 1}/{len(chapters)}'})
            continue
        parts, meta, at = [], [';FFMETADATA1', f'title={ab["title"]}'], 0
        for i, (t, secs) in enumerate(chapters):
            x = narration(secs, sr, seed + i, ab['f0'])
            chime = bell(midi(76), 2.5, sr) * 0.12 + bell(midi(83), 2.5, sr) * 0.08
            x[:len(chime)] += chime[:len(x)]
            parts.append(x)
            meta += ['[CHAPTER]', 'TIMEBASE=1/1000', f'START={at * 1000}', f'END={(at + secs) * 1000}', f'title={t}']
            at += secs
        wav = WORK / f'ab{seed:08x}.wav'
        write_wav(wav, np.concatenate(parts), sr)
        chap = WORK / f'ab{seed:08x}.txt'
        chap.write_text('\n'.join(meta) + '\n')
        desc = ab.get('desc') or (book['desc'] if book else '')
        job(dst, wav, cover, 'm4b', {'title': ab['title'], 'album': ab['title'], 'artist': ab['author'], 'album_artist': ab['author'],
                                     'composer': ab['narrator'], 'comment': f'Read by {ab["narrator"]}', 'genre': ab['genre'],
                                     'date': str(ab['year']), 'description': desc, 'media_type': '2'}, chap)


# ---------------------------------------------------------------------------------------------------------------------
# Comics: CBZ files of simple drawn panels, with ComicInfo.xml.

BALLOONS = ['Did you see that?', 'Kestrel, come in.', 'Hold the line!', 'Not tonight.', 'Over there!', 'We go at dawn.', 'Too quiet.',
            'Who’s there?', 'Trust me.', 'Run!', 'It’s working!', 'Look up.', 'The tide’s turning.', 'Stay close.', 'Almost there.', 'Again?']
COMICS = [
    dict(series='Kestrel', title=f'Kestrel No. {n}', number=n, volume=1, count=12, writer='Mina Hale, Ken Ito', penciller='Ken Ito', year=2024 + n // 3,
         month=1 + (n * 2) % 12, art=[3, 7, 4, 7, 12][n - 1], credit='Hale & Ito', pages=32 if n == 4 else 12, genre='Adventure, Science fiction',
         summary=f'Issue {n} of Kestrel. ' + ['A courier pilot takes one last job over the storm belt.', 'The storm belt has a hole in it, and something lives there.',
                                              'Grounded in a town that should not exist, Kestrel makes a deal.', 'A night flight, a stolen map and a promise to keep.',
                                              'The town has a price, and the bill comes due.'][n - 1]) for n in range(1, 6)
] + [
    dict(series='Tidewater', title='Tidewater', number=1, volume=1, count=1, writer='June Okoro', penciller='June Okoro', year=2025, month=4, art=1,
         credit='June Okoro', pages=12, genre='Drama', summary='A girl and her grandmother keep the last tide mill on the estuary running for one more summer.'),
    dict(series='Ember Run', title='Ember Run Vol. 1', number=1, volume=1, count=3, writer='Yui Sato, Owen Price', penciller='Owen Price', year=2023, month=9,
         art=11, credit='Sato & Price', pages=12, genre='Fantasy, Adventure', summary='Two couriers race a wildfire across the old forest with a message nobody wants delivered.'),
    dict(series='Ember Run', title='Ember Run Vol. 2', number=2, volume=2, count=3, writer='Yui Sato, Owen Price', penciller='Owen Price', year=2024, month=10,
         art=3, credit='Sato & Price', pages=12, genre='Fantasy, Adventure', summary='The fire is out. The forest is not done with them.'),
    dict(series='Static Bloom', title='Static Bloom', number=1, volume=1, count=1, writer='M. Arden', penciller='M. Arden', year=2023, month=6, art=5,
         credit='M. Arden', pages=12, genre='Science fiction', summary='A one-shot: a radio operator hears a garden growing on the far side of the static.'),
    dict(series='Low Orbit', title='Low Orbit Vol. 1', number=1, volume=1, count=2, writer='Kade Ruiz', penciller='Kade Ruiz', year=2024, month=2, art=6,
         credit='Kade Ruiz', pages=12, genre='Science fiction', summary='Life on a repair station in low orbit, where the view is everything and the coffee is terrible.'),
    dict(series='Harbor Lights', title='Harbor Lights Vol. 3', number=3, volume=3, count=4, writer='Ines Vela', penciller='Ines Vela', year=2025, month=7,
         art=2, credit='Ines Vela', pages=12, genre='Romance, Drama', summary='The harbour festival, a missing boat and a long-overdue conversation.'),
    dict(series='Paper Moon', title='Paper Moon Vol. 1', number=1, volume=1, count=2, writer='T. Lund', penciller='T. Lund', year=2022, month=11, art=0,
         credit='T. Lund', pages=12, genre='Fantasy', summary='In a town made of paper, the moon is cut out fresh every night.'),
]
LAYOUTS = [[1, 2], [2, 1], [2, 2], [1, 1], [3, 1], [1, 2, 1], [2, 1, 2]]


def comic_page(c, page, W=800, H=1200):
    rng = np.random.default_rng(seed_of(c['title'], page))
    S = 2
    img = Image.new('RGB', (W * S, H * S), '#f4f1ea')
    d = ImageDraw.Draw(img)
    bg, sun, ground, ink = BP[c['art'] % len(BP)]
    sky_options = [bg, '#9fc6d9', '#f2cf86', '#2f2840', '#dfe7ef', '#e8dcc8']
    rows = LAYOUTS[int(rng.integers(len(LAYOUTS)))]
    m, g = 36 * S, 18 * S
    rh = (H * S - 2 * m - g * (len(rows) - 1)) / len(rows)
    fb = font(22 * S, 800)
    for ri, cols in enumerate(rows):
        cw = (W * S - 2 * m - g * (cols - 1)) / cols
        for ci in range(cols):
            x0, y0 = m + ci * (cw + g), m + ri * (rh + g)
            x1, y1 = x0 + cw, y0 + rh
            panel = Image.new('RGB', (int(cw), int(rh)), sky_options[int(rng.integers(len(sky_options)))])
            pd = ImageDraw.Draw(panel)
            pw, ph = panel.size
            r = ph * rng.uniform(0.12, 0.22)
            sx, sy = pw * rng.uniform(0.2, 0.8), ph * rng.uniform(0.2, 0.45)
            pd.ellipse([sx - r, sy - r, sx + r, sy + r], fill=sun)
            pts = [(0, ph)] + [(pw * i / 6, ph * rng.uniform(0.55, 0.8)) for i in range(7)] + [(pw, ph)]
            pd.polygon(pts, fill=ground)
            for _ in range(int(rng.integers(1, 3))):  # figures
                fx, fy, fs = pw * rng.uniform(0.2, 0.8), ph * 0.92, ph * rng.uniform(0.18, 0.32)
                pd.rounded_rectangle([fx - fs * 0.22, fy - fs * 0.75, fx + fs * 0.22, fy], radius=fs * 0.15, fill='#141414')
                pd.ellipse([fx - fs * 0.16, fy - fs * 1.1, fx + fs * 0.16, fy - fs * 0.78], fill='#141414')
            img.paste(panel, (int(x0), int(y0)))
            d.rectangle([x0, y0, x1, y1], outline='#111111', width=5 * S)
            if rng.random() < 0.65:  # a speech balloon
                text = BALLOONS[int(rng.integers(len(BALLOONS)))].upper()
                lines = wrap(text, fb, cw * 0.5)
                tw = max(fb.getlength(line) for line in lines)
                bw, bh = tw + 44 * S, len(lines) * 28 * S + 30 * S
                bx, by = x0 + rng.uniform(16 * S, max(17 * S, cw - bw - 16 * S)), y0 + 16 * S
                d.polygon([(bx + bw * 0.3, by + bh - 6 * S), (bx + bw * 0.45, by + bh - 6 * S), (bx + bw * 0.28, by + bh + 26 * S)],
                          fill='white', outline='#111111', width=3 * S)
                d.ellipse([bx, by, bx + bw, by + bh], fill='white', outline='#111111', width=3 * S)
                for li, line in enumerate(lines):
                    d.text((bx + bw / 2, by + 15 * S + li * 28 * S + 22 * S), line, font=fb, fill='#111111', anchor='ms')
    fn = font(16 * S, 700)
    d.text((W * S / 2, H * S - 12 * S), str(page), font=fn, fill='#555555', anchor='ms')
    return img.resize((W, H), Image.LANCZOS)


def make_comics():
    root = MEDIA / 'Comics'
    for c in COMICS:
        folder = root / c['series']
        folder.mkdir(parents=True, exist_ok=True)
        dst = folder / f'{c["series"]} #{c["number"]:02d}.cbz'
        if dst.exists():
            continue
        tmp = dst.with_suffix('.part')
        pages = [book_art(c['art'], c['title'], c['credit'], 800, 1200)] + [comic_page(c, p) for p in range(1, c['pages'])]
        info = ('<?xml version="1.0" encoding="utf-8"?>\n<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
                'xmlns:xsd="http://www.w3.org/2001/XMLSchema">\n'
                f'  <Title>{xml(c["title"])}</Title>\n  <Series>{xml(c["series"])}</Series>\n  <Number>{c["number"]}</Number>\n'
                f'  <Count>{c["count"]}</Count>\n  <Volume>{c["volume"]}</Volume>\n  <Summary>{xml(c["summary"])}</Summary>\n'
                f'  <Notes>Generated test comic for Seedy (CC0)</Notes>\n  <Year>{c["year"]}</Year>\n  <Month>{c["month"]}</Month>\n  <Day>1</Day>\n'
                f'  <Writer>{xml(c["writer"])}</Writer>\n  <Penciller>{xml(c["penciller"])}</Penciller>\n'
                f'  <Publisher>Hollow Pine Comics</Publisher>\n  <Genre>{xml(c["genre"])}</Genre>\n  <PageCount>{len(pages)}</PageCount>\n'
                '  <LanguageISO>en</LanguageISO>\n  <Manga>No</Manga>\n  <Pages>\n' +
                ''.join(f'    <Page Image="{i}" ImageWidth="800" ImageHeight="1200"{" Type=" + chr(34) + "FrontCover" + chr(34) if i == 0 else ""}/>\n'
                        for i in range(len(pages))) + '  </Pages>\n</ComicInfo>\n')
        with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_STORED) as z:
            for i, p in enumerate(pages):
                z.writestr(f'{i:03d}.jpg', jpeg_bytes(p, 82))
            z.writestr('ComicInfo.xml', info, compress_type=zipfile.ZIP_DEFLATED)
        tmp.rename(dst)


if __name__ == '__main__':
    WORK.mkdir(parents=True, exist_ok=True)
    for old in list(WORK.glob('job*')):
        old.unlink()
    make_books()
    print('books ready')
    make_comics()
    print('comics ready')
    make_music()
    make_audiobooks()
    script = WORK / 'encode.sh'
    lines = ['#!/bin/sh', '# Written by make-music-books.py: encodes its WAVs with the Jellyfin image\'s ffmpeg, then removes them.', 'set -e',
             'cd "$(dirname "$0")"', 'export FF=/usr/lib/jellyfin-ffmpeg/ffmpeg']
    for i, j in enumerate(jobs):
        (WORK / f'job{i:03d}.sh').write_text(j + '\n')
    lines += ['ls job*.sh 2>/dev/null | xargs -r -P "$(nproc)" -n 1 sh', 'cd / && rm -rf "$(dirname "$0")"',
              'echo "music and audiobooks ready"']
    script.write_text('\n'.join(lines) + '\n')
    print(f'{len(jobs)} audio files to encode')
