/* Copies text; falls back to a hidden textarea where the async clipboard isn't allowed (plain http). */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch { /* fall through */ }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  } catch {
    return false;
  }
}

/* Saves a blob as a file through a temporary <a download>; the object URL lives on for [revokeAfterMs]. */
export function saveBlob(name: string, blob: Blob, revokeAfterMs = 1000) {
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(href), revokeAfterMs);
}

export const downloadText = (name: string, text: string, type = 'text/plain;charset=utf-8') => saveBlob(name, new Blob([text], { type }));
