import { useEffect, useState } from 'preact/hooks';
import type { BaseItem } from '../../../api/types';
import { formatNumber, t, tn, tx } from '../../../lib/i18n';
import { Icon } from '../../../ui/icons';
import { toast } from '../../../ui/overlay';
import { codecName } from '../../server/format';
import { clearInSeerr, deleteItems, getDeletePreview, type DeleteGroup, type DeletePreview } from './actions';
import { ItemButton, ItemCheck, ItemDialog, ItemField } from './dialog';

/* Delete board: what goes (by season, the video files, then artwork, subtitles and NFO files), where it lives, the
   Seerr side, and the title typed out before the red button works. Also deletes a library selection. */

/* "252 GB", "38 GB", "0.2 GB", "640 MB". */
export function deleteSize(bytes: number): string {
  const gb = bytes / 1024 ** 3;
  const num = (v: number, d: number) => formatNumber(Number(v.toFixed(d)), { maximumFractionDigits: d });
  if (gb >= 1024) return `${num(gb / 1024, gb >= 10240 ? 0 : 1)} TB`;
  if (gb >= 10) return `${num(gb, 0)} GB`;
  if (gb >= 0.1) return `${num(gb, 1)} GB`;
  const mb = bytes / 1024 ** 2;
  if (mb >= 1) return `${num(mb, 0)} MB`;
  return bytes >= 1024 ? `${num(bytes / 1024, 0)} KB` : `${num(bytes, 0)} B`;
}

const quality = (g: DeleteGroup) => [g.height ? `${g.height >= 2000 ? 2160 : g.height}p` : '', codecName(g.codec)].filter(Boolean).join(' ');

const KIND_PLURAL: Record<string, [string, string]> = {
  Movie: ['{n} movie', '{n} movies'], Series: ['{n} series', '{n} series'], Season: ['{n} season', '{n} seasons'], Episode: ['{n} episode', '{n} episodes']
};

function countLabel(items: BaseItem[]): string {
  const types = new Set(items.map((i) => i.Type));
  const forms = types.size === 1 ? KIND_PLURAL[items[0].Type] : undefined;
  return forms ? tn(items.length, forms[0], forms[1]) : tn(items.length, '{n} item', '{n} items');
}

interface Line { key: string; name: string; sub: string; bytes: number }

function lines(item: BaseItem, groups: DeleteGroup[], multi: boolean): Line[] {
  const seasons = groups.filter((g) => g.kind === 'season');
  const last = Math.max(...seasons.map((g) => g.index ?? 0));
  return groups.filter((g) => !multi || g.kind !== 'extras').map((g, i) => {
    const q = quality(g);
    if (g.kind === 'season') {
      const eps = item.Status === 'Continuing' && g.index === last ? tn(g.episodes, '{n} episode so far', '{n} episodes so far') : tn(g.episodes, '{n} episode', '{n} episodes');
      return { key: g.id ?? String(i), name: g.name ?? t('Season {n}', { n: g.index ?? '' }), sub: [eps, q].filter(Boolean).join(' · '), bytes: g.bytes };
    }
    if (g.kind === 'extras') return { key: 'extras', name: t('Artwork, subtitles and NFO files'), sub: tn(g.files, '{n} file', '{n} files'), bytes: g.bytes };
    if (multi) return { key: item.Id, name: item.Name ?? '', sub: q, bytes: g.bytes };
    if (item.Type === 'Season') return { key: 'media', name: t('Episodes'), sub: [tn(g.episodes, '{n} episode', '{n} episodes'), q].filter(Boolean).join(' · '), bytes: g.bytes };
    return { key: 'media', name: g.files === 1 ? t('Video file') : tn(g.files, '{n} video file', '{n} video files'), sub: q, bytes: g.bytes };
  });
}

export function DeleteDialog({ items, onClose, onDone }: { items: BaseItem[]; onClose: () => void; onDone: (deleted: string[]) => void }) {
  const [preview, setPreview] = useState<DeletePreview | null>(null);
  const [failed, setFailed] = useState(false);
  const [seerr, setSeerr] = useState(true);
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const multi = items.length > 1;
  const single = items[0];

  useEffect(() => {
    getDeletePreview(items.map((i) => i.Id)).then(setPreview, () => setFailed(true));
  }, [items]);

  const byId = new Map((preview?.items ?? []).map((p) => [p.id, p]));
  const allowed = items.filter((i) => byId.get(i.Id)?.canDelete ?? (failed && i.CanDelete));
  const bytes = preview ? allowed.reduce((n, i) => n + (byId.get(i.Id)?.bytes ?? 0), 0) : 0;
  const withSeerr = allowed.map((i) => byId.get(i.Id)?.seerr).filter((s): s is NonNullable<typeof s> => !!s);
  const monitored = withSeerr.filter((s) => s.monitored);
  const arr = monitored[0]?.arr ?? 'Sonarr';

  const target = multi ? countLabel(items) : single.Name ?? '';
  const seasons = preview && !multi ? (byId.get(single.Id)?.groups.filter((g) => g.kind === 'season').length ?? 0) : 0;
  const sub = [multi ? countLabel(items) : seasons ? tn(seasons, '{n} season', '{n} seasons') : '', preview ? deleteSize(bytes) : ''].filter(Boolean).join(' · ');

  let rows: Line[] = [];
  if (preview) {
    if (multi) {
      rows = allowed.map((i) => {
        const p = byId.get(i.Id)!;
        const media = p.groups.find((g) => g.kind !== 'extras');
        return { key: i.Id, name: i.Name ?? '', sub: media ? quality(media) : '', bytes: p.bytes };
      });
    } else if (byId.get(single.Id)) {
      rows = lines(single, byId.get(single.Id)!.groups, false);
    }
  }
  const paths = !multi ? byId.get(single.Id)?.paths ?? [] : [];
  const skipped = preview ? items.length - allowed.length : 0;
  const ready = !busy && allowed.length > 0 && typed.trim().toLowerCase() === target.toLowerCase() && (!!preview || failed);

  const run = async () => {
    if (!ready) return;
    setBusy(true);
    const ids = allowed.map((i) => i.Id);
    try {
      await deleteItems(ids);
    } catch {
      toast(t('Couldn’t delete {name}', { name: target }), 'error');
      setBusy(false);
      return;
    }
    if (seerr && withSeerr.length) {
      const results = await Promise.allSettled(withSeerr.map((s) => clearInSeerr(s.mediaType, s.tmdbId)));
      if (results.some((r) => r.status === 'rejected')) toast(t('Deleted, but Seerr couldn’t be cleared'), 'error');
    }
    toast(t('Deleted {name}', { name: target }));
    onDone(ids);
  };

  return (
    <ItemDialog
      id="dl" width={600} danger onClose={onClose}
      title={t('Delete {name}?', { name: target })}
      sub={sub || (failed ? undefined : <span class="skel skel-line" style={{ width: '120px', display: 'inline-block' }} />)}
      actions={
        <>
          <ItemButton onClick={onClose}>{t('Cancel')}</ItemButton>
          <ItemButton kind="danger" icon="trash" disabled={!ready} onClick={() => void run()}>
            {busy ? t('Deleting…') : preview ? t('Delete {size}', { size: deleteSize(bytes) }) : t('Delete')}
          </ItemButton>
        </>
      }
    >
      <p class="dl-text">{multi
        ? t('This deletes their files from the server’s disk, and everyone loses their watch progress for them. It can’t be undone.')
        : t('This deletes the files from the server’s disk, and everyone loses their watch progress for it. It can’t be undone.')}</p>
      {(rows.length > 0 || paths.length > 0 || !preview) && (
        <div class="dl-list" aria-busy={!preview && !failed ? 'true' : undefined}>
          {!preview && !failed && [0, 1].map((i) => <div key={i} class="dl-row"><span class="skel skel-line" style={{ width: '40%' }} /></div>)}
          {rows.map((r) => (
            <div key={r.key} class="dl-row">
              <span class="dl-row-text"><span class="dl-row-name">{r.name}</span>{r.sub && <span class="dl-row-sub">{r.sub}</span>}</span>
              <span class="dl-row-size">{deleteSize(r.bytes)}</span>
            </div>
          ))}
          {paths.map((p) => <div key={p} class="dl-path"><Icon name="folder" size={15} />{p}</div>)}
        </div>
      )}
      {skipped > 0 && <p class="dl-note">{tn(skipped, 'You can’t delete {n} of these, so it stays.', 'You can’t delete {n} of these, so they stay.')}</p>}
      {withSeerr.length > 0 && (
        <ItemCheck checked={seerr} onChange={setSeerr} label={t('Also clear it in Seerr')} help={t('So it can be requested again later')} />
      )}
      {monitored.length > 0 && (
        <div class="dl-warn" role="note">
          <Icon name="warn" size={18} />
          <span>{multi
            ? t('{arr} still monitors {n} of them, so it may download them again. Unmonitor them in {arr} if you want them gone for good.', { arr, n: monitored.length })
            : t('{arr} still monitors {name}, so it may download it again. Unmonitor it in {arr} if you want it gone for good.', { arr, name: target })}</span>
        </div>
      )}
      <ItemField label={tx('Type {name} to confirm', { name: <b>{target}</b> })} htmlFor="dl-confirm">
        <input id="dl-confirm" class="im-input" type="text" autocomplete="off" spellcheck={false} value={typed}
          onInput={(e) => setTyped((e.target as HTMLInputElement).value)} onKeyDown={(e) => { if (e.key === 'Enter') void run(); }} />
      </ItemField>
    </ItemDialog>
  );
}
