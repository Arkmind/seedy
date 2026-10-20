#!/usr/bin/env python3
"""Prints the CHANGELOG.md notes for one version (the text under its "## <version>" heading).

    python3 scripts/changelog.py 0.2.0
"""
import re
import sys

version = sys.argv[1].lstrip('v') if len(sys.argv) > 1 else sys.exit(__doc__)
text = open('CHANGELOG.md', encoding='utf-8').read()
m = re.search(rf'^## \[?{re.escape(version)}\]?[^\n]*\n(.*?)(?=^## |\Z)', text, re.M | re.S)
print(m.group(1).strip() if m else f'Seedy {version}')
