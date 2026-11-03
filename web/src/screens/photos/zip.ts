import { SERVER_URL, getSession } from '../../api/client';
import type { BaseItem } from '../../api/types';
import { t } from '../../lib/i18n';
import { toast } from '../../ui/overlay';

/* Download several photos and videos as one .zip, built here: each original comes from Jellyfin's download endpoint
   (so the profile's download permission applies) and goes in uncompressed, since photos and videos don't shrink. */

const CRC = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(data: Uint8Array): number {
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) c = CRC[(c ^ data[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function dosTime(d: Date): [number, number] {
  const time = (d.getHours() << 11) | (d.getMinutes() << 5) | Math.floor(d.getSeconds() / 2);
  const date = (Math.max(0, d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  return [time, date];
}

const LIMIT = 0xffffffff - 1_000_000;

export async function downloadZip(name: string, items: BaseItem[]): Promise<void> {
  const token = getSession()?.token ?? '';
  const enc = new TextEncoder();
  const parts: BlobPart[] = [];
  const central: Uint8Array<ArrayBuffer>[] = [];
  const used = new Set<string>();
  let offset = 0;
  let failed = 0;
  const now = new Date();
  for (let i = 0; i < items.length; i++) {
    const it = items[i];
    if (i % 5 === 0) toast(t('Preparing {name}.zip · {n} of {total}', { name, n: i + 1, total: items.length }), 'pending', 4000);
    let data: Uint8Array<ArrayBuffer>;
    let file = it.Name;
    try {
      const r = await fetch(`${SERVER_URL}/Items/${it.Id}/Download?ApiKey=${encodeURIComponent(token)}`);
      if (!r.ok) throw new Error(String(r.status));
      const cd = r.headers.get('Content-Disposition') ?? '';
      const m = /filename\*=UTF-8''([^;]+)/i.exec(cd) ?? /filename="?([^";]+)"?/i.exec(cd);
      if (m) file = decodeURIComponent(m[1]);
      data = new Uint8Array(await r.arrayBuffer());
    } catch {
      failed++;
      continue;
    }
    if (offset + data.length > LIMIT) { toast(t('That’s too much for one download: pick fewer'), 'error'); return; }
    let unique = file;
    for (let n = 2; used.has(unique.toLowerCase()); n++) unique = file.replace(/(\.[^.]*)?$/, ` (${n})$1`);
    used.add(unique.toLowerCase());
    const nameBytes = enc.encode(unique);
    const crc = crc32(data);
    const [time, date] = dosTime(now);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true);
    local.setUint16(6, 0x0800, true);
    local.setUint16(8, 0, true);
    local.setUint16(10, time, true);
    local.setUint16(12, date, true);
    local.setUint32(14, crc, true);
    local.setUint32(18, data.length, true);
    local.setUint32(22, data.length, true);
    local.setUint16(26, nameBytes.length, true);
    local.setUint16(28, 0, true);
    parts.push(local.buffer, nameBytes, data);
    const entry = new DataView(new ArrayBuffer(46));
    entry.setUint32(0, 0x02014b50, true);
    entry.setUint16(4, 20, true);
    entry.setUint16(6, 20, true);
    entry.setUint16(8, 0x0800, true);
    entry.setUint16(10, 0, true);
    entry.setUint16(12, time, true);
    entry.setUint16(14, date, true);
    entry.setUint32(16, crc, true);
    entry.setUint32(20, data.length, true);
    entry.setUint32(24, data.length, true);
    entry.setUint16(28, nameBytes.length, true);
    entry.setUint32(42, offset, true);
    const row = new Uint8Array(46 + nameBytes.length);
    row.set(new Uint8Array(entry.buffer), 0);
    row.set(nameBytes, 46);
    central.push(row);
    offset += 30 + nameBytes.length + data.length;
  }
  if (!central.length) { toast(t('Couldn’t download these'), 'error'); return; }
  const size = central.reduce((s, c) => s + c.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, central.length, true);
  end.setUint16(10, central.length, true);
  end.setUint32(12, size, true);
  end.setUint32(16, offset, true);
  const blob = new Blob([...parts, ...central, end.buffer], { type: 'application/zip' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `${name.replace(/[\\/:*?"<>|]+/g, ' ').trim() || 'Photos'}.zip`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
  toast(failed ? t('Downloaded {n}, {failed} couldn’t be read', { n: central.length, failed }) : t('Downloaded {name}.zip', { name }));
}
