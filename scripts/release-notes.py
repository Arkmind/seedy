#!/usr/bin/env python3
"""Reads a GitHub release's description in Seedy's format (CONTRIBUTING.md, "Release notes").

    # What's Changed
    - <!-- icon:play_button --> Your own “Still watching?” rules
      2–4 episodes, 90 min–3 h, or 1–3 movies
      <!-- fr: Vos propres règles « Toujours là ? » | 2 à 4 épisodes, 90 min à 3 h, ou 1 à 3 films -->

    **Full changelog**: https://github.com/Arkmind/seedy/compare/v0.4.6...v0.4.7

The Android update screen reads the same text (android/core/…/update/ReleaseNotes.kt); keep both in step.

Usage (the description on stdin):
    release-notes.py check            the items as the app shows them, and what's wrong (exit 1 when something is)
    release-notes.py play --out DIR   DIR/whatsnew-en-US for Google Play (exit 1, no file, when there are no items)
    release-notes.py plain            the description without the icon and French comments (Jellyfin's catalogue)
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICONS_FILE = os.path.join(ROOT, 'android/ui/src/main/kotlin/io/github/arkmind/seedy/ui/icons/ReleaseIcons.kt')
DEFAULT_ICON = 'sparkles'
PLAY_LIMIT = 500
# What fits the phone's update screen: a title on one line, a line under it on two at most, four items.
TITLE_MAX, TEXT_MAX, ITEMS_MAX = 34, 90, 4

BULLET = re.compile(r'^[-*]\s+(.*)$')
ICON = re.compile(r'<!--\s*icon:\s*([a-z0-9_]+)\s*-->')
FRENCH = re.compile(r'<!--\s*fr:\s*(.*?)\s*-->')
COMMENT = re.compile(r'<!--.*?-->')
GENERATED = re.compile(r'\sby @\S+ in https?://\S+\s*$')
LINK = re.compile(r'\[([^\]]*)]\([^)]*\)')
MARKS = re.compile(r'\*\*|__|`')


def plain(s):
    return MARKS.sub('', LINK.sub(r'\1', COMMENT.sub('', s))).strip()


def parse(body):
    """The "What's Changed" items: dicts with icon, title, text, fr_title, fr_text."""
    items, cur = [], None

    def close():
        nonlocal cur
        if cur and cur['title'] and not GENERATED.search(cur['raw']):
            cur['text'] = ' '.join(cur.pop('lines')) or None
            cur.pop('raw')
            items.append(cur)
        cur = None

    def french(item, s):
        title, _, text = (p.strip() for p in s.partition('|'))
        item['fr_title'], item['fr_text'] = title or None, text or None

    for raw in body.replace('\r', '').split('\n'):
        line = raw.rstrip()
        trimmed = line.strip()
        if trimmed.startswith('#'):
            if items or cur:
                break
            continue
        if trimmed.lower().startswith('**full changelog'):
            break
        b = BULLET.match(line)
        if b:
            close()
            rest = b.group(1)
            icon = ICON.search(rest)
            cur = {'raw': rest, 'icon': icon.group(1) if icon else DEFAULT_ICON, 'title': plain(rest), 'lines': [],
                   'fr_title': None, 'fr_text': None}
            fr = FRENCH.search(rest)
            if fr:
                french(cur, fr.group(1))
        elif cur and trimmed and raw.startswith(' '):
            fr = FRENCH.search(trimmed)
            if fr:
                french(cur, fr.group(1))
            text = plain(trimmed)
            if text:
                cur['lines'].append(text)
        elif not trimmed:
            pass
        elif cur:
            close()
    close()
    return items


def icon_keys():
    with open(ICONS_FILE, encoding='utf-8') as f:
        return set(re.findall(r'^\s*"([a-z0-9_]+)" to ', f.read(), re.M))


def play_lines(items):
    return ['• ' + it['title'] + (' — ' + it['text'] if it['text'] else '') for it in items]


def play_text(items):
    """One "• Title — line" per item, as many whole items as fit Google Play's 500 characters."""
    out = ''
    for line in play_lines(items):
        if len(out) + len(line) + (1 if out else 0) > PLAY_LIMIT:
            break
        out += ('\n' if out else '') + line
    return out


def check(body):
    items, keys, problems = parse(body), icon_keys(), []
    if not items:
        problems.append('no "What\'s Changed" items: the update screen shows an empty list and Google Play gets no notes')
    if len(items) > ITEMS_MAX:
        problems.append(f'{len(items)} items: the phone screen fits {ITEMS_MAX}')
    for it in items:
        print(f"[{it['icon']}] {it['title']}" + (f"\n    {it['text']}" if it['text'] else ''))
        if it['fr_title']:
            print(f"    fr: {it['fr_title']}" + (f" | {it['fr_text']}" if it['fr_text'] else ''))
        if it['icon'] not in keys:
            problems.append(f"unknown icon '{it['icon']}' ({it['title']}): the app shows sparkles instead")
        if len(it['title']) > TITLE_MAX:
            problems.append(f"title longer than {TITLE_MAX} characters, it wraps: {it['title']}")
        if it['text'] and len(it['text']) > TEXT_MAX:
            problems.append(f"line under '{it['title']}' longer than {TEXT_MAX} characters")
    if not re.search(r'^\*\*Full changelog\*\*: https://github\.com/\S+/compare/\S+\.\.\.\S+$', body, re.M | re.I):
        problems.append('no "**Full changelog**: https://github.com/<owner>/<repo>/compare/vA...vB" line')
    if items:
        play = play_text(items)
        print(f'\nGoogle Play ({len(play)}/{PLAY_LIMIT}):\n{play}')
        if play.count('\n') + 1 < len(items):
            problems.append(f'Google Play gets only the first items ({PLAY_LIMIT} characters)')
    for p in problems:
        print(f'::warning::{p}' if os.environ.get('GITHUB_ACTIONS') else f'! {p}', file=sys.stderr)
    return 1 if problems else 0


def strip_comments(body):
    """The description for Jellyfin's plugin catalogue: no icon or French comments (lines left empty go too)."""
    out = []
    for line in body.replace('\r', '').split('\n'):
        cleaned = re.sub(r'<!--\s*(icon|fr):.*?-->\s*', '', line).rstrip()
        if cleaned.strip() or not line.strip():
            out.append(cleaned)
    return '\n'.join(out).strip() + '\n'


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('mode', choices=['check', 'play', 'plain'])
    ap.add_argument('--out', help='play: the directory for whatsnew-en-US')
    a = ap.parse_args()
    body = sys.stdin.read()
    if a.mode == 'check':
        return check(body)
    if a.mode == 'plain':
        sys.stdout.write(strip_comments(body))
        return 0
    items = parse(body)
    if not items:
        msg = 'no "What\'s Changed" items in the release text: Google Play gets no release notes'
        print(f'::warning::{msg}' if os.environ.get('GITHUB_ACTIONS') else msg, file=sys.stderr)
        return 1
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, 'whatsnew-en-US'), 'w', encoding='utf-8') as f:
        f.write(play_text(items) + '\n')
    print(play_text(items))
    return 0


if __name__ == '__main__':
    sys.exit(main())
