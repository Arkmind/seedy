import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { imageUrl } from '../../../api/images';
import type { BaseItem } from '../../../api/types';
import { locale, t } from '../../../lib/i18n';
import { Icon } from '../../../ui/icons';
import { toast } from '../../../ui/overlay';
import {
  deleteImage, downloadRemoteImage, getItemImages, getRemoteImageProviders, getRemoteImages, languageName, previewUrl,
  providerName, uploadImage, type ImageInfo, type ImageProviderInfo, type RemoteImageInfo
} from './api';
import { ItemButton, ItemDialog, ItemSelect } from './dialog';
import './images.css';

/* Edit images (ItemImages board): the item's slots on the left (Poster, Backdrops, Logo, Thumbnail, Banner), the
   chosen slot's art from the providers on the right. Every change goes to the server straight away. */

type SlotKind = 'Primary' | 'Backdrop' | 'Logo' | 'Thumb' | 'Banner';
const SLOTS: SlotKind[] = ['Primary', 'Backdrop', 'Logo', 'Thumb', 'Banner'];

interface SlotText { name: string; heading: (n: number) => string; use: string; remove: string; ratio: string; empty: string }

/* What the item's main image is called: a still for an episode, a cover for music, a photo for a person. */
function primaryWord(item: BaseItem): 'poster' | 'still' | 'cover' | 'photo' {
  if (item.Type === 'Episode' || item.Type === 'TvChannel' || item.Type === 'Program' || item.Type === 'Video') return 'still';
  if (item.Type === 'MusicAlbum' || item.Type === 'Audio' || item.Type === 'MusicArtist' || item.Type === 'Playlist') return 'cover';
  if (item.Type === 'Person') return 'photo';
  return 'poster';
}

function slotText(slot: SlotKind, item: BaseItem): SlotText {
  switch (slot) {
    case 'Primary': {
      const w = primaryWord(item);
      if (w === 'still') return { name: t('Still'), heading: (n) => t('Stills from providers · {n}', { n }), use: t('Use as still'), remove: t('Remove still'), ratio: '16 / 9', empty: t('None yet · cards and the details page use it') };
      if (w === 'cover') return { name: t('Cover'), heading: (n) => t('Covers from providers · {n}', { n }), use: t('Use as cover'), remove: t('Remove cover'), ratio: '1', empty: t('None yet · cards and the details page use it') };
      if (w === 'photo') return { name: t('Photo'), heading: (n) => t('Photos from providers · {n}', { n }), use: t('Use as photo'), remove: t('Remove photo'), ratio: '2 / 3', empty: t('None yet · cards and the details page use it') };
      return { name: t('Poster'), heading: (n) => t('Posters from providers · {n}', { n }), use: t('Use as poster'), remove: t('Remove poster'), ratio: '2 / 3', empty: t('None yet · cards and the details page use it') };
    }
    case 'Backdrop': return { name: t('Backdrops'), heading: (n) => t('Backdrops from providers · {n}', { n }), use: t('Add backdrop'), remove: t('Remove backdrop'), ratio: '16 / 9', empty: t('None yet · behind the title') };
    case 'Logo': return { name: t('Logo'), heading: (n) => t('Logos from providers · {n}', { n }), use: t('Use as logo'), remove: t('Remove logo'), ratio: '16 / 9', empty: t('None yet · title art over the backdrop') };
    case 'Thumb': return { name: t('Thumbnail'), heading: (n) => t('Thumbnails from providers · {n}', { n }), use: t('Use as thumbnail'), remove: t('Remove thumbnail'), ratio: '16 / 9', empty: t('None yet · 16:9 cards') };
    case 'Banner': return { name: t('Banner'), heading: (n) => t('Banners from providers · {n}', { n }), use: t('Use as banner'), remove: t('Remove banner'), ratio: '5.4 / 1', empty: t('None yet · some TV apps use it') };
  }
}

const size = (w?: number, h?: number) => (w && h ? `${w} × ${h}` : '');
const format = (path?: string) => (path?.match(/\.([a-z0-9]+)$/i)?.[1] ?? '').toUpperCase().replace('JPEG', 'JPG');

interface Remote { state: 'loading' | 'ready' | 'error'; images: RemoteImageInfo[] }
type Placed = { url: string; provider?: string } | null;

/* Language filter values: '' any, NO_TEXT for art without words, else a language code. */
const NO_TEXT = '-';

export function ImagesDialog({ item, onClose, onDone }: { item: BaseItem; onClose: () => void; onDone: () => void }) {
  const [images, setImages] = useState<ImageInfo[] | null>(null);
  const [providers, setProviders] = useState<ImageProviderInfo[] | null>(null);
  const [slot, setSlot] = useState<SlotKind>('Primary');
  const [remote, setRemote] = useState<Partial<Record<SlotKind, Remote>>>({});
  const [provider, setProvider] = useState('');
  const [language, setLanguage] = useState('');
  /* What this dialog put in each slot, by the provider's url: backdrops by their index, the others at 0. Jellyfin
     can't say where the art it holds came from, so only these show "In use". */
  const [placed, setPlaced] = useState<Partial<Record<SlotKind, Placed[]>>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [changed, setChanged] = useState(false);
  const file = useRef<HTMLInputElement>(null);

  const reloadImages = () => getItemImages(item.Id).then(setImages);

  useEffect(() => {
    const ctrl = new AbortController();
    getItemImages(item.Id, ctrl.signal).then(setImages, (e: Error) => {
      if (ctrl.signal.aborted) return;
      setImages([]);
      toast(t('Couldn’t load the images: {reason}', { reason: e.message }), 'error');
    });
    getRemoteImageProviders(item.Id, ctrl.signal).then(setProviders, () => !ctrl.signal.aborted && setProviders([]));
    // First focus on the chosen slot's button (the dialog would pick the provider filter).
    requestAnimationFrame(() => document.querySelector<HTMLElement>('.ig-slot.is-on .ig-btn')?.focus());
    return () => ctrl.abort();
  }, [item.Id]);

  const slotProviders = useMemo(() => (providers ?? []).filter((p) => p.SupportedImages?.includes(slot)).map((p) => p.Name), [providers, slot]);

  // The chosen slot's art, fetched once per slot.
  useEffect(() => {
    if (!providers || remote[slot]) return;
    if (!slotProviders.length) { setRemote((r) => ({ ...r, [slot]: { state: 'ready', images: [] } })); return; }
    const ctrl = new AbortController();
    const kind = slot;
    setRemote((r) => ({ ...r, [kind]: { state: 'loading', images: [] } }));
    getRemoteImages(item.Id, kind, ctrl.signal).then(
      (res) => setRemote((r) => ({ ...r, [kind]: { state: 'ready', images: res.Images ?? [] } })),
      () => { if (!ctrl.signal.aborted) setRemote((r) => ({ ...r, [kind]: { state: 'error', images: [] } })); }
    );
    // Left before it answered: forget it, so coming back asks again.
    return () => {
      ctrl.abort();
      setRemote((r) => { if (r[kind]?.state !== 'loading') return r; const next = { ...r }; delete next[kind]; return next; });
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slot, providers]);

  const pick = (s: SlotKind) => {
    if (s === slot) return;
    setSlot(s);
    setProvider('');
    setLanguage('');
  };

  const now = remote[slot];
  const languages = useMemo(() => {
    const codes = [...new Set((now?.images ?? []).map((i) => i.Language || NO_TEXT))];
    return codes.sort((a, b) => (a === NO_TEXT ? -1 : b === NO_TEXT ? 1 : languageName(a).localeCompare(languageName(b), locale())));
  }, [now]);
  const shown = (now?.images ?? []).filter((i) =>
    (!provider || i.ProviderName === provider) && (!language || (language === NO_TEXT ? !i.Language : i.Language === language)));

  const of = (kind: SlotKind) => (images ?? []).filter((i) => i.ImageType === kind).sort((a, b) => (a.ImageIndex ?? 0) - (b.ImageIndex ?? 0));
  const inUse = (kind: SlotKind, url?: string) => !!url && !!placed[kind]?.some((p) => p?.url === url);

  /* Puts (or with null, takes out) what sits at `index`. Removing a backdrop moves the ones after it down, as the
     server does. */
  const mark = (kind: SlotKind, entry: Placed, index = 0) =>
    setPlaced((m) => {
      if (kind !== 'Backdrop') return { ...m, [kind]: entry ? [entry] : [] };
      const list = [...(m[kind] ?? [])];
      while (list.length < index) list.push(null);
      if (entry) list[index] = entry;
      else list.splice(index, 1);
      return { ...m, [kind]: list };
    });

  const use = async (img: RemoteImageInfo) => {
    if (!img.Url || busy) return;
    const kind = slot;
    const before = of('Backdrop').length;
    setBusy('url:' + img.Url);
    try {
      await downloadRemoteImage(item.Id, kind, img.Url);
      await reloadImages();
      mark(kind, { url: img.Url, provider: img.ProviderName }, kind === 'Backdrop' ? before : 0);
      setChanged(true);
      toast(kind === 'Backdrop' ? t('Backdrop added') : t('Image saved'));
    } catch (e) {
      toast(t('Couldn’t save the image: {reason}', { reason: (e as Error).message }), 'error');
    } finally {
      setBusy(null);
    }
  };

  const remove = async (kind: SlotKind, index?: number) => {
    if (busy) return;
    setBusy(`del:${kind}:${index ?? 0}`);
    try {
      await deleteImage(item.Id, kind, kind === 'Backdrop' ? index : undefined);
      await reloadImages();
      mark(kind, null, index ?? 0);
      setChanged(true);
      toast(kind === 'Backdrop' ? t('Backdrop removed') : t('Image removed'));
    } catch (e) {
      toast(t('Couldn’t remove the image: {reason}', { reason: (e as Error).message }), 'error');
    } finally {
      setBusy(null);
    }
  };

  const upload = async (f: File) => {
    if (busy) return;
    if (!f.type.startsWith('image/')) { toast(t('That file isn’t an image'), 'error'); return; }
    const kind = slot;
    const before = of('Backdrop').length;
    setBusy('upload');
    try {
      await uploadImage(item.Id, kind, f);
      await reloadImages();
      // An upload has no provider url; it replaces what this dialog had put there.
      mark(kind, kind === 'Backdrop' ? { url: 'upload:' + f.name } : null, kind === 'Backdrop' ? before : 0);
      setChanged(true);
      toast(t('Uploaded {name}', { name: f.name }));
    } catch (e) {
      toast(t('Couldn’t upload the image: {reason}', { reason: (e as Error).message }), 'error');
    } finally {
      setBusy(null);
      if (file.current) file.current.value = '';
    }
  };

  const text = slotText(slot, item);
  const finish = changed ? onDone : onClose;

  return (
    <ItemDialog
      id="ig"
      width={1200}
      onClose={finish}
      overline={t('Edit images')}
      title={item.Name}
      sub={t('Pick a slot, then choose from providers or upload your own')}
      foot={<span class="ig-saved"><Icon name="check" size={16} stroke={2.6} color="var(--seedy-ok)" />{t('Changes save right away')}</span>}
      actions={<ItemButton kind="primary" onClick={finish}>{t('Done')}</ItemButton>}
    >
      <div class="ig-layout">
        <ul class="ig-slots" aria-label={t('Image slots')}>
          {SLOTS.map((kind) => (
            <SlotRow key={kind} item={item} kind={kind} on={kind === slot} list={of(kind)} loading={!images} placed={placed[kind] ?? []}
              busy={busy} onPick={() => pick(kind)} onRemove={(i) => remove(kind, i)} />
          ))}
        </ul>
        <section id="ig-panel" class="ig-panel" aria-labelledby="ig-panel-h" aria-busy={now?.state === 'loading' ? 'true' : undefined}>
          <div class="ig-panel-head">
            <h3 id="ig-panel-h" class="ig-panel-title">{text.heading(shown.length)}</h3>
            <span class="ig-filters">
              <ItemSelect class="ig-provider" label={t('Image source')} value={provider} onChange={setProvider} disabled={!slotProviders.length}>
                <option value="">{t('All providers')}</option>
                {slotProviders.map((p) => <option key={p} value={p}>{providerName(p)}</option>)}
              </ItemSelect>
              <ItemSelect class="ig-language" label={t('Image language')} value={language} onChange={setLanguage} disabled={!languages.length}>
                <option value="">{t('Any language')}</option>
                {languages.map((l) => <option key={l} value={l}>{l === NO_TEXT ? t('No text') : languageName(l)}</option>)}
              </ItemSelect>
              <button type="button" class="ig-upload" onClick={() => file.current?.click()} disabled={!!busy} aria-busy={busy === 'upload' ? 'true' : undefined}>
                {busy === 'upload' ? <span aria-hidden="true" class="im-spin" /> : <Icon name="uploadUp" size={17} stroke={2.2} />}
                {t('Upload')}
              </button>
              <input ref={file} type="file" accept="image/*" class="ig-file" tabIndex={-1} aria-hidden="true"
                onChange={(e) => { const f = (e.target as HTMLInputElement).files?.[0]; if (f) void upload(f); }} />
            </span>
          </div>
          {!now || now.state === 'loading' ? (
            <div class={'ig-grid is-' + slot} aria-hidden="true">
              {Array.from({ length: slot === 'Primary' ? 10 : 9 }, (_, i) => (
                <span key={i} class="ig-tile"><span class="ig-img skel" style={{ aspectRatio: text.ratio }} /><span class="skel skel-line" /></span>
              ))}
            </div>
          ) : now.state === 'error' ? (
            <div class="ig-note">
              <span>{t('Seedy couldn’t reach the image providers.')}</span>
              <ItemButton icon="refresh" onClick={() => setRemote((r) => { const next = { ...r }; delete next[slot]; return next; })}>{t('Try again')}</ItemButton>
            </div>
          ) : !shown.length ? (
            <p class="ig-note">
              {now.images.length ? t('Nothing matches these filters.')
                : slotProviders.length ? t('The providers have nothing for this slot. Upload your own.')
                : t('No provider offers this kind of image. Upload your own.')}
            </p>
          ) : (
            <div class={'ig-grid is-' + slot}>
              {shown.map((img, i) => (
                <Tile key={img.Url ?? i} id={'ig-m-' + i} img={img} ratio={text.ratio} kind={slot} action={text.use}
                  used={inUse(slot, img.Url)} busy={busy === 'url:' + img.Url} locked={!!busy} onUse={() => use(img)} />
              ))}
            </div>
          )}
        </section>
      </div>
    </ItemDialog>
  );
}

function SlotRow({ item, kind, on, list, loading, placed, busy, onPick, onRemove }: {
  item: BaseItem; kind: SlotKind; on: boolean; list: ImageInfo[]; loading: boolean; placed: Placed[];
  busy: string | null; onPick: () => void; onRemove: (index?: number) => void;
}) {
  const text = slotText(kind, item);
  const first = list[0];
  const has = !!first;
  const src = (info: ImageInfo, width: number) => imageUrl(item.Id, kind, { tag: info.ImageTag, index: kind === 'Backdrop' ? info.ImageIndex ?? 0 : undefined, maxWidth: width, quality: kind === 'Logo' ? 100 : 90 });
  const word = primaryWord(item);

  let sub = text.empty;
  if (loading) sub = '';
  else if (kind === 'Backdrop') sub = has ? t('Behind the title') : text.empty;
  else if (has) {
    const dims = size(first.Width, first.Height);
    const from = placed[0]?.provider ? providerName(placed[0].provider) : format(first.Path);
    if (kind === 'Primary') sub = [dims, from].filter(Boolean).join(' · ');
    else if (kind === 'Logo') sub = t('Title art · {format}', { format: format(first.Path) || 'PNG' });
    else if (kind === 'Thumb') sub = dims ? t('16:9 cards · {size}', { size: dims }) : t('16:9 cards');
    else sub = dims ? t('Some TV apps use it · {size}', { size: dims }) : t('Some TV apps use it');
  }

  const art = (() => {
    if (kind === 'Backdrop' && has) {
      return (
        <span class="ig-thumbs">
          {list.map((b, i) => (
            <button key={b.ImageTag ?? i} type="button" class="ig-thumb" aria-label={t('Remove backdrop {n}', { n: i + 1 })}
              disabled={!!busy} onClick={(e) => { e.stopPropagation(); onRemove(b.ImageIndex ?? i); }}>
              <img src={src(b, 120)} alt="" loading="lazy" />
              <span aria-hidden="true" class="ig-thumb-x">
                {busy === `del:Backdrop:${b.ImageIndex ?? i}` ? <span class="im-spin" /> : <Icon name="close" size={14} stroke={2.6} />}
              </span>
            </button>
          ))}
        </span>
      );
    }
    const cls = 'ig-art is-' + (kind === 'Primary' ? word : kind.toLowerCase());
    return (
      <span aria-hidden="true" class={cls}>
        {kind === 'Logo' && has && <span class="ig-checks" />}
        {has ? <img src={src(first, kind === 'Primary' ? 120 : 200)} alt="" /> : <span class="ig-dash" />}
      </span>
    );
  })();

  return (
    <li class={'ig-slot' + (on ? ' is-on' : '')} aria-current={on ? 'true' : undefined} onClick={onPick}>
      {art}
      <span class="ig-slot-text">
        <span id={'ig-s-' + kind} class="ig-slot-name">{kind === 'Backdrop' && has ? t('Backdrops · {n}', { n: list.length }) : text.name}</span>
        {sub && <span class="ig-slot-sub">{sub}</span>}
      </span>
      <span class="ig-slot-actions">
        <button type="button" class="ig-btn" aria-controls="ig-panel" aria-describedby={'ig-s-' + kind}
          onClick={(e) => { e.stopPropagation(); onPick(); }}>
          {kind === 'Backdrop' ? t('Add') : has ? t('Change') : t('Find')}
        </button>
        {has && kind !== 'Backdrop' && (
          <button type="button" class="ig-btn is-icon" aria-label={text.remove} disabled={!!busy} onClick={(e) => { e.stopPropagation(); onRemove(); }}>
            {busy === `del:${kind}:0` ? <span aria-hidden="true" class="im-spin" /> : <Icon name="bin" size={16} stroke={2.2} />}
          </button>
        )}
      </span>
    </li>
  );
}

function Tile({ id, img, ratio, kind, action, used, busy, locked, onUse }: {
  id: string; img: RemoteImageInfo; ratio: string; kind: SlotKind; action: string; used: boolean; busy: boolean; locked: boolean; onUse: () => void;
}) {
  const [broken, setBroken] = useState(false);
  const src = img.ThumbnailUrl || previewUrl(img.Url, 'w780');
  const meta = [size(img.Width, img.Height), providerName(img.ProviderName), languageName(img.Language)].filter(Boolean).join(' · ');
  return (
    <div class={'ig-tile' + (busy ? ' is-busy' : '')}>
      <span class={'ig-img' + (kind === 'Logo' ? ' is-logo' : '')} style={{ aspectRatio: ratio }}>
        {kind === 'Logo' && <span aria-hidden="true" class="ig-checks" />}
        {src && !broken && <img src={src} alt="" loading="lazy" onError={() => setBroken(true)} />}
        {used && <span class="ig-used"><Icon name="check" size={12} stroke={3} />{t('In use')}</span>}
        <span class="ig-over">
          {!used && (
            <button type="button" class="ig-use" aria-describedby={id} onClick={onUse} disabled={locked} aria-busy={busy ? 'true' : undefined}>
              {busy && <span aria-hidden="true" class="im-spin" />}
              {action}
            </button>
          )}
          {img.Url && <a class="ig-view" href={img.Url} target="_blank" rel="noreferrer" aria-describedby={id}>{t('View')}</a>}
        </span>
      </span>
      <span id={id} class="ig-meta">{meta}</span>
    </div>
  );
}
