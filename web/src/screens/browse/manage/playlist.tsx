import type { RefObject } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { get, SERVER_URL } from '../../../api/client';
import { primaryUrl } from '../../../api/images';
import type { BaseItem, ItemsResult } from '../../../api/types';
import { currentUserId } from '../../../lib/app-state';
import { t, tn } from '../../../lib/i18n';
import { Icon, type IconName } from '../../../ui/icons';
import { MenuItem, Popover, toast } from '../../../ui/overlay';
import { avatarColour } from '../../server/format';
import { createPlaylist, getShareWith, type SharePerson } from './actions';
import { ItemButton, ItemDialog, ItemField } from './dialog';

/* New playlist board: a name, what it starts with, and who can see it (only me, people I pick with what they may
   do, or everyone on the server). */

type Who = 'me' | 'people' | 'all';
interface Share { person: SharePerson; index: number; canEdit: boolean }

/* "20 episodes on the server, in order", "1 movie · 2026". */
function useStartsWith(items: BaseItem[]): string | undefined {
  const [line, setLine] = useState<string>();
  useEffect(() => {
    if (items.length > 1) { setLine(items.map((i) => i.Name).filter(Boolean).slice(0, 3).join(', ') + (items.length > 3 ? '…' : '')); return; }
    const [it] = items;
    // A new empty playlist (Music · New playlist) starts with nothing.
    if (!it) { setLine(undefined); return; }
    if (it.Type === 'Series' || it.Type === 'Season') {
      get<ItemsResult>(`/Users/${currentUserId()}/Items`, { ParentId: it.Id, IncludeItemTypes: 'Episode', Recursive: true, IsMissing: false, Limit: 0 })
        .then((r) => setLine(tn(r.TotalRecordCount, '{n} episode on the server, in order', '{n} episodes on the server, in order')), () => setLine(undefined));
    } else {
      setLine([it.SeriesName, it.ProductionYear].filter(Boolean).join(' · ') || undefined);
    }
  }, [items]);
  return line;
}

export function NewPlaylistDialog({ items, name: initial, onClose, onDone }: { items: BaseItem[]; name?: string; onClose: () => void; onDone: () => void }) {
  const [name, setName] = useState(initial ?? '');
  const [who, setWho] = useState<Who>('me');
  const [people, setPeople] = useState<SharePerson[] | null>(null);
  const [shares, setShares] = useState<Share[]>([]);
  const [busy, setBusy] = useState(false);
  const addRef = useRef<HTMLButtonElement>(null);
  const [adding, setAdding] = useState(false);
  const line = useStartsWith(items);
  const [first] = items;
  const poster = first ? primaryUrl(first, 120) : undefined;

  useEffect(() => { getShareWith().then(setPeople); }, []);

  const pickable = (people ?? []).filter((p) => !shares.some((s) => s.person.id === p.id));
  const create = async () => {
    if (!name.trim() || busy) return;
    setBusy(true);
    try {
      await createPlaylist({
        Name: name.trim(),
        Ids: items.map((i) => i.Id),
        Users: who === 'people' ? shares.map((s) => ({ UserId: s.person.id, CanEdit: s.canEdit })) : [],
        IsPublic: who === 'all',
        // An empty one comes from the music library: it's a music playlist.
        MediaType: items.length ? undefined : 'Audio'
      });
      toast(t('Created {name}', { name: name.trim() }));
      onDone();
    } catch {
      toast(t('Couldn’t create the playlist'), 'error');
      setBusy(false);
    }
  };

  const option = (value: Who, icon: IconName, title: string, help: string) => (
    <button type="button" role="radio" aria-checked={who === value ? 'true' : 'false'} class="np-who" onClick={() => setWho(value)}>
      <span aria-hidden="true" class="np-who-icon"><Icon name={icon} size={20} /></span>
      <span class="np-who-text"><span class="np-who-name">{title}</span><span class="np-who-help">{help}</span></span>
      <span aria-hidden="true" class="np-radio" />
    </button>
  );

  return (
    <ItemDialog
      id="np" width={600} onClose={onClose} title={t('New playlist')}
      actions={
        <>
          <ItemButton onClick={onClose}>{t('Cancel')}</ItemButton>
          <ItemButton kind="primary" disabled={!name.trim() || busy} onClick={() => void create()}>{busy ? t('Creating…') : t('Create playlist')}</ItemButton>
        </>
      }
    >
      <ItemField label={t('Name')} htmlFor="np-name">
        <input id="np-name" class="im-input" type="text" aria-label={t('Playlist name')} value={name} maxLength={200}
          onInput={(e) => setName((e.target as HTMLInputElement).value)} onKeyDown={(e) => { if (e.key === 'Enter') void create(); }} />
      </ItemField>
      {first && (
        <div class="np-start">
          <span aria-hidden="true" class="np-start-poster">{poster && <img src={poster} alt="" />}</span>
          <span class="np-start-text">
            <span class="np-start-name">{items.length > 1 ? t('Starts with {n} titles', { n: items.length }) : t('Starts with {name}', { name: first.Name ?? '' })}</span>
            {line && <span class="np-start-sub">{line}</span>}
          </span>
        </div>
      )}
      <div role="radiogroup" aria-labelledby="np-who" class="np-whos">
        <span id="np-who" class="np-label">{t('Who can see it')}</span>
        {option('me', 'person', t('Only me'), t('Nobody else sees it'))}
        {people !== null && people.length > 0 && option('people', 'people', t('People I pick'), t('They see it in their Playlists'))}
        {who === 'people' && (
          <div class="np-people">
            {shares.map((s) => (
              <div key={s.person.id} class="np-person">
                <Avatar person={s.person} index={s.index} />
                <span class="np-person-name">{s.person.name}</span>
                <span class="im-select np-can">
                  <select class="im-input" aria-label={t('{name} can', { name: s.person.name })} value={s.canEdit ? 'edit' : 'watch'}
                    onChange={(e) => { const canEdit = (e.target as HTMLSelectElement).value === 'edit'; setShares((l) => l.map((x) => (x.person.id === s.person.id ? { ...x, canEdit } : x))); }}>
                    <option value="edit">{t('Can edit')}</option>
                    <option value="watch">{t('Can watch')}</option>
                  </select>
                  <Icon name="chevronDown" size={16} />
                </span>
                <button type="button" class="np-remove" aria-label={t('Remove {name}', { name: s.person.name })} onClick={() => setShares((l) => l.filter((x) => x.person.id !== s.person.id))}>
                  <Icon name="close" size={15} stroke={2.4} />
                </button>
              </div>
            ))}
            {pickable.length > 0 && (
              <>
                <button ref={addRef} type="button" class="np-add" aria-haspopup="menu" aria-expanded={adding ? 'true' : 'false'} onClick={() => setAdding((a) => !a)}>
                  <Icon name="plus" size={16} stroke={2.6} />{t('Add someone')}
                </button>
                <Popover anchor={addRef as RefObject<HTMLElement>} open={adding} onClose={() => setAdding(false)} align="start" width={260}>
                  <div class="menu" role="menu">
                    {pickable.map((p) => (
                      <MenuItem key={p.id} label={p.name} onSelect={() => {
                        setShares((l) => [...l, { person: p, index: (people ?? []).indexOf(p) + 1, canEdit: false }]);
                        setAdding(false);
                      }} />
                    ))}
                  </div>
                </Popover>
              </>
            )}
          </div>
        )}
        {option('all', 'globe', t('Everyone on this server'), t('Shows up for every profile'))}
      </div>
    </ItemDialog>
  );
}

function Avatar({ person, index }: { person: SharePerson; index: number }) {
  const [failed, setFailed] = useState(false);
  return (
    <span aria-hidden="true" class="np-avatar" style={{ background: avatarColour(index) }}>
      {person.hasImage && !failed
        ? <img src={`${SERVER_URL}/Users/${person.id}/Images/Primary?maxWidth=64&quality=90`} alt="" onError={() => setFailed(true)} />
        : (person.name[0] ?? '?').toUpperCase()}
    </span>
  );
}
