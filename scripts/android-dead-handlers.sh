#!/usr/bin/env bash
# Fails when an Android screen has a control that does nothing: an empty click handler (`onX = {}`, `.tap {}`,
# `.clickable {}`, `{}` handed to a *Button or *Icon, or a row, item, button or menu entry ending in `{}`).
# Allowed: keyboard actions (`KeyboardActions(onSearch = {})`), default parameter values (`onX: () -> Unit = {}`) and
# lines that say why with a `// no-op:` comment. A panel that only keeps taps from what's under it uses blockTaps().
set -euo pipefail
cd "$(dirname "$0")/.."

empty='\{[[:space:]]*\}'
pattern="\\bon[A-Z][A-Za-z0-9_]*[[:space:]]*=[[:space:]]*$empty"
pattern+="|\\.(tap|clickable)[[:space:]]*$empty"
pattern+="|\\.(tap|clickable)\\([^()]*\\)[[:space:]]*$empty"
pattern+="|(Button|Icon)[A-Za-z0-9]*\\([^()]*(\\([^()]*\\)[^()]*)*,[[:space:]]*$empty\\)"
# A row, item, button or menu entry whose trailing lambda (its click) is empty: `PickRow(…) {}`.
pattern+="|\\b(item|[A-Z][A-Za-z0-9]*(Row|Item|Button|Entry))\\(.*\\)[[:space:]]*$empty[[:space:]})]*\$"

hits=$(grep -rnE --include='*.kt' "$pattern" android/*/src/main | grep -vE 'KeyboardActions\(|// no-op:' || true)
if [ -n "$hits" ]; then
  echo "Controls that do nothing (wire them, hide them, or say why with a // no-op: comment):"
  echo "$hits"
  exit 1
fi
echo "No empty click handlers."
