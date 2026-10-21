import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { personImageUrl, primaryUrl } from '../../../api/images';
import type { BaseItem, NameId, Person } from '../../../api/types';
import { fmtDate, fmtDayMonth } from '../../../lib/format';
import { locale, t } from '../../../lib/i18n';
import { Switch } from '../../../ui/controls';
import { Icon } from '../../../ui/icons';
import { toast } from '../../../ui/overlay';
import { getFullItem, getMetadataEditor, idName, idsFor, sortIds, typeName, updateItem, type ItemDto, type MetadataEditorInfo } from './api';
import { ItemButton, ItemDialog, ItemField, ItemSelect } from './dialog';
import './edit-metadata.css';

/* Edit metadata (ItemEditMetadata board): Details as boarded, then Cast and crew, Provider IDs and Display and
   language from the same kit. The whole item is loaded once and sent back edited. */

type Tab = 'details' | 'people' | 'ids' | 'display';

/* Jellyfin's MetadataField: what "Locked" keeps a refresh from replacing. (Runtime is one too, but the server takes
   the runtime from the file and ignores an edited one, so Seedy neither shows it nor touches its lock.) */
type LockField = 'Name' | 'Overview' | 'Genres' | 'Tags' | 'Studios' | 'ProductionLocations' | 'Cast' | 'OfficialRating';

interface Form {
  Name: string; OriginalTitle: string; ForcedSortName: string;
  PremiereDate: string; EndDate: string; Status: string; OfficialRating: string; CustomRating: string;
  ProductionYear: string; ParentIndexNumber: string; IndexNumber: string;
  Overview: string; Tagline: string;
  Genres: string[]; Tags: string[]; Studios: string[]; ProductionLocations: string[];
  CommunityRating: string; CriticRating: string;
  People: Person[];
  ProviderIds: Record<string, string>;
  PreferredMetadataLanguage: string; PreferredMetadataCountryCode: string; DisplayOrder: string;
  AirTime: string; AirDays: string[];
  LockData: boolean;
}

/* Which form value each lock covers. */
const LOCKS: Record<LockField, keyof Form> = {
  Name: 'Name', Overview: 'Overview', Genres: 'Genres', Tags: 'Tags', Studios: 'Studios',
  ProductionLocations: 'ProductionLocations', Cast: 'People', OfficialRating: 'OfficialRating'
};

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const STATUSES = ['Continuing', 'Ended', 'Unreleased'];
const PERSON_KINDS = ['Actor', 'GuestStar', 'Director', 'Writer', 'Producer', 'Creator', 'Composer'];

const day = (s?: string | null) => (s ? s.slice(0, 10) : '');
const str = (n?: number | null) => (n === undefined || n === null ? '' : String(n));

function toForm(d: ItemDto): Form {
  return {
    Name: d.Name ?? '', OriginalTitle: d.OriginalTitle ?? '', ForcedSortName: d.ForcedSortName ?? '',
    PremiereDate: day(d.PremiereDate), EndDate: day(d.EndDate), Status: d.Status ?? '',
    OfficialRating: d.OfficialRating ?? '', CustomRating: d.CustomRating ?? '',
    ProductionYear: str(d.ProductionYear),
    ParentIndexNumber: str(d.ParentIndexNumber), IndexNumber: str(d.IndexNumber),
    Overview: d.Overview ?? '', Tagline: d.Taglines?.[0] ?? '',
    Genres: [...(d.Genres ?? [])], Tags: [...(d.Tags ?? [])], Studios: (d.Studios ?? []).map((s) => s.Name),
    ProductionLocations: [...(d.ProductionLocations ?? [])],
    CommunityRating: str(d.CommunityRating), CriticRating: str(d.CriticRating),
    People: (d.People ?? []).map((p) => ({ ...p })),
    ProviderIds: { ...(d.ProviderIds ?? {}) },
    PreferredMetadataLanguage: d.PreferredMetadataLanguage ?? '', PreferredMetadataCountryCode: d.PreferredMetadataCountryCode ?? '',
    DisplayOrder: d.DisplayOrder ?? '', AirTime: d.AirTime ?? '', AirDays: [...(d.AirDays ?? [])],
    LockData: !!d.LockData
  };
}

/* A number field: '' → null, anything unreadable → NaN (refused before saving). */
function num(s: string): number | null {
  const v = s.trim().replace(',', '.');
  if (!v) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : NaN;
}

const isoDay = (s: string) => (s ? `${s}T00:00:00.0000000Z` : null);

export function EditMetadataDialog({ item, onClose, onDone }: { item: BaseItem; onClose: () => void; onDone: () => void }) {
  const [tab, setTab] = useState<Tab>('details');
  const [loaded, setLoaded] = useState<{ dto: ItemDto; editor: MetadataEditorInfo; initial: Form } | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [form, setForm] = useState<Form | null>(null);
  /* Locks the person set or lifted by hand; everything else follows the item's locks plus what was edited. */
  const [lockSet, setLockSet] = useState<Partial<Record<LockField, boolean>>>({});
  const [saving, setSaving] = useState(false);
  /* Switching tabs never shrinks the dialog: it would jump to re-centre. */
  const panel = useRef<HTMLDivElement>(null);
  const [minHeight, setMinHeight] = useState(0);
  const pick = (next: Tab) => {
    const h = panel.current?.offsetHeight ?? 0;
    setMinHeight((m) => Math.max(m, h));
    setTab(next);
  };

  useEffect(() => {
    const ctrl = new AbortController();
    setFailed(false);
    Promise.all([getFullItem(item.Id, ctrl.signal), getMetadataEditor(item.Id, ctrl.signal)]).then(
      ([dto, editor]) => {
        if (ctrl.signal.aborted) return;
        const initial = toForm(dto);
        setLoaded({ dto, editor, initial });
        setForm(initial);
      },
      (e: Error) => {
        if (ctrl.signal.aborted || e?.name === 'AbortError') return;
        setFailed(true);
        toast(t('Couldn’t load the metadata: {reason}', { reason: e.message }), 'error');
      }
    );
    return () => ctrl.abort();
  }, [item.Id, attempt]);

  // The dialog opens before the fields exist, so its first focus lands on Close; move it to Title once they do.
  useEffect(() => {
    if (!loaded) return;
    const a = document.activeElement;
    if (!a || a === document.body || a.classList.contains('im-close')) document.getElementById('em-name')?.focus();
  }, [loaded]);

  const initialLocks = useMemo(() => new Set(loaded?.dto.LockedFields ?? []), [loaded]);
  const edited = (f: LockField) => !!form && !!loaded && JSON.stringify(form[LOCKS[f]]) !== JSON.stringify(loaded.initial[LOCKS[f]]);
  const locked = (f: LockField) => lockSet[f] ?? (initialLocks.has(f) || edited(f));
  const toggleLock = (f: LockField) => setLockSet((s) => ({ ...s, [f]: !locked(f) }));

  const set = <K extends keyof Form>(key: K, value: Form[K]) => setForm((f) => (f ? { ...f, [key]: value } : f));

  const save = async () => {
    if (!form || !loaded) return;
    const { dto, initial } = loaded;
    const numbers = {
      CommunityRating: num(form.CommunityRating), CriticRating: num(form.CriticRating), ProductionYear: num(form.ProductionYear),
      ParentIndexNumber: num(form.ParentIndexNumber), IndexNumber: num(form.IndexNumber)
    };
    const bad = Object.entries(numbers).find(([, v]) => Number.isNaN(v));
    if (bad) { toast(t('{field} needs a number', { field: fieldName(bad[0], item) }), 'error'); return; }
    if (!form.Name.trim()) { toast(t('The title can’t be empty'), 'error'); setTab('details'); return; }

    // A new first-aired date moves the year with it, unless the year was set apart from the date.
    let year = numbers.ProductionYear;
    if (form.PremiereDate !== initial.PremiereDate && form.ProductionYear === initial.ProductionYear
      && (!initial.ProductionYear || initial.PremiereDate.slice(0, 4) === initial.ProductionYear)) {
      year = form.PremiereDate ? Number(form.PremiereDate.slice(0, 4)) : year;
    }
    // New names go without an id: the server makes the studio.
    const studios = form.Studios.map((name) => dto.Studios?.find((s) => s.Name === name) ?? ({ Name: name } as NameId));
    const ids = Object.fromEntries(Object.entries(form.ProviderIds).map(([k, v]) => [k, v.trim()]).filter(([, v]) => v));
    const shown = Object.keys(LOCKS) as LockField[];
    // What the server only reports (and can't even read back, for trickplay) stays out of the reply.
    const { Trickplay: _tp, MediaSources: _ms, MediaStreams: _st, Chapters: _ch, ImageBlurHashes: _bh, ...rest } = dto;
    const body: ItemDto = {
      ...(rest as ItemDto),
      Name: form.Name.trim(),
      OriginalTitle: form.OriginalTitle.trim() || undefined,
      ForcedSortName: form.ForcedSortName.trim() || undefined,
      PremiereDate: isoDay(form.PremiereDate) ?? undefined,
      EndDate: isoDay(form.EndDate) ?? undefined,
      Status: form.Status || undefined,
      OfficialRating: form.OfficialRating || undefined,
      CustomRating: form.CustomRating || undefined,
      ProductionYear: year ?? undefined,
      ParentIndexNumber: numbers.ParentIndexNumber ?? undefined,
      IndexNumber: numbers.IndexNumber ?? undefined,
      Overview: form.Overview,
      Taglines: form.Tagline.trim() ? [form.Tagline.trim(), ...(dto.Taglines ?? []).slice(1)] : [],
      Genres: form.Genres,
      Tags: form.Tags,
      Studios: studios,
      ProductionLocations: form.ProductionLocations,
      CommunityRating: numbers.CommunityRating ?? undefined,
      CriticRating: numbers.CriticRating ?? undefined,
      People: form.People,
      ProviderIds: ids,
      PreferredMetadataLanguage: form.PreferredMetadataLanguage || undefined,
      PreferredMetadataCountryCode: form.PreferredMetadataCountryCode || undefined,
      DisplayOrder: form.DisplayOrder || undefined,
      AirTime: form.AirTime.trim() || undefined,
      AirDays: form.AirDays,
      // Locks Seedy doesn't show (Runtime) stay as they were.
      LockedFields: [...(dto.LockedFields ?? []).filter((f) => !shown.includes(f as LockField)), ...shown.filter(locked)],
      LockData: form.LockData
    };
    // Cleared fields go as null.
    for (const k of Object.keys(body) as (keyof ItemDto)[]) if (body[k] === undefined) (body as Record<string, unknown>)[k as string] = null;
    setSaving(true);
    try {
      await updateItem(body);
      toast(t('Saved {name}', { name: body.Name }));
      onDone();
    } catch (e) {
      toast(t('Couldn’t save: {reason}', { reason: (e as Error).message }), 'error');
      setSaving(false);
    }
  };

  const dto = loaded?.dto;
  const sub = [typeName(item.Type), dto?.Path ?? (item as ItemDto).Path, refreshedLine(dto)].filter(Boolean).join(' · ');
  const people = form?.People.length ?? item.People?.length ?? 0;
  const tabs: { id: Tab; label: string }[] = [
    { id: 'details', label: t('Details') },
    { id: 'people', label: people ? t('Cast and crew · {n}', { n: people }) : t('Cast and crew') },
    { id: 'ids', label: t('Provider IDs') },
    { id: 'display', label: t('Display and language') }
  ];

  return (
    <ItemDialog
      id="em"
      width={1040}
      onClose={onClose}
      poster={primaryUrl(item, 120) ?? null}
      overline={t('Edit metadata')}
      title={item.Name}
      sub={sub}
      foot={
        <>
          <Switch checked={!!form?.LockData} onChange={(v) => set('LockData', v)} label={t('Lock all fields')} describedBy="em-lockall-help" disabled={!form} />
          <span class="em-lockall">
            <span class="em-lockall-name">{t('Lock all fields')}</span>
            <span id="em-lockall-help">{t('Fields you edit lock on their own, so a refresh won’t undo them')}</span>
          </span>
        </>
      }
      actions={
        <>
          <ItemButton onClick={onClose}>{t('Cancel')}</ItemButton>
          <ItemButton kind="primary" icon="check" stroke={2.6} onClick={save} busy={saving} disabled={!form}>{t('Save changes')}</ItemButton>
        </>
      }
    >
      <nav aria-label={t('Metadata sections')} class="em-tabs">
        {tabs.map((x) => (
          <button key={x.id} type="button" class="em-tab" aria-current={tab === x.id ? 'true' : undefined} onClick={() => pick(x.id)}>{x.label}</button>
        ))}
      </nav>
      <div ref={panel} class="em-panel" style={minHeight ? { minHeight: minHeight + 'px' } : undefined}>
      {!form || !loaded ? (
        failed ? <LoadFailed onRetry={() => setAttempt((n) => n + 1)} /> : <Loading />
      ) : tab === 'details' ? (
        <DetailsTab item={item} form={form} set={set} editor={loaded.editor} locked={locked} toggleLock={toggleLock} />
      ) : tab === 'people' ? (
        <PeopleTab form={form} set={set} locked={locked} toggleLock={toggleLock} />
      ) : tab === 'ids' ? (
        <IdsTab item={item} form={form} set={set} editor={loaded.editor} />
      ) : (
        <DisplayTab item={item} form={form} set={set} editor={loaded.editor} locked={locked} toggleLock={toggleLock} />
      )}
      </div>
    </ItemDialog>
  );
}

function refreshedLine(dto?: ItemDto): string {
  const at = dto?.DateLastRefreshed ? new Date(dto.DateLastRefreshed) : undefined;
  const added = dto?.DateCreated ? new Date(dto.DateCreated) : undefined;
  const when = (d: Date) => (d.getFullYear() === new Date().getFullYear() ? fmtDayMonth(d) : fmtDate(d));
  if (at && !Number.isNaN(at.getTime()) && at.getFullYear() > 1) return t('last refreshed {date}', { date: when(at) });
  if (added && !Number.isNaN(added.getTime())) return t('added {date}', { date: when(added) });
  return '';
}

function fieldName(key: string, item: BaseItem): string {
  switch (key) {
    case 'CommunityRating': return t('Community rating');
    case 'CriticRating': return t('Critic rating');
    case 'ProductionYear': return t('Year');
    case 'ParentIndexNumber': return t('Season');
    case 'IndexNumber': return item.Type === 'Season' ? t('Season number') : t('Episode');
    default: return key;
  }
}

type Setter = <K extends keyof Form>(key: K, value: Form[K]) => void;
interface LockProps { locked: (f: LockField) => boolean; toggleLock: (f: LockField) => void }

/* The "Locked" chip in a field's label row. Unlocked fields keep it out of sight until the field is pointed at or
   focused, as on the board where only the locked ones show it. */
function LockChip({ field, name, locked, toggleLock }: LockProps & { field: LockField; name: string }) {
  const on = locked(field);
  return (
    <button type="button" class="em-lock" aria-pressed={on ? 'true' : 'false'} aria-label={on ? t('Unlock {name}', { name }) : t('Lock {name}', { name })} onClick={() => toggleLock(field)}>
      <Icon name={on ? 'lock' : 'lockOpen'} size={13} stroke={2.4} />
      {on ? t('Locked') : t('Lock')}
    </button>
  );
}

function Text({ id, value, onInput, placeholder, label, inputMode }: {
  id: string; value: string; onInput: (v: string) => void; placeholder?: string; label?: string; inputMode?: 'numeric' | 'decimal' | 'text';
}) {
  return (
    <input id={id} type="text" class="im-input" value={value} placeholder={placeholder} aria-label={label} inputMode={inputMode}
      onInput={(e) => onInput((e.target as HTMLInputElement).value)} />
  );
}

/* A date box. With a placeholder ("Still airing"), it reads as text until it holds a date or has focus. */
function DateBox({ id, value, onInput, placeholder }: { id: string; value: string; onInput: (v: string) => void; placeholder?: string }) {
  const [focused, setFocused] = useState(false);
  const asDate = !placeholder || !!value || focused;
  return (
    <input id={id} type={asDate ? 'date' : 'text'} class="im-input" value={value} placeholder={placeholder}
      onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
      onInput={(e) => onInput((e.target as HTMLInputElement).value)} />
  );
}

/* Esc inside a small editor (a chip being typed) stops there instead of closing the dialog. The dialog listens on the
   document while capturing, so this listens on the window, which hears it first. */
function useEscape(target: { current: HTMLElement | null }, active: boolean, fn: () => void) {
  const fnRef = useRef(fn);
  fnRef.current = fn;
  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || !target.current || !target.current.contains(document.activeElement)) return;
      e.stopPropagation();
      fnRef.current();
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [active, target]);
}

/* Names in a box: each with a remove button, then "+ Add …" which turns into a text box (Enter adds, Esc stops). */
function Chips({ id, values, onChange, addLabel, inputLabel }: {
  id: string; values: string[]; onChange: (v: string[]) => void; addLabel: string; inputLabel: string;
}) {
  const [adding, setAdding] = useState(false);
  const [text, setText] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const addBtn = useRef<HTMLButtonElement>(null);
  const stop = () => { setAdding(false); setText(''); requestAnimationFrame(() => addBtn.current?.focus()); };
  useEscape(input, adding, stop);
  useEffect(() => { if (adding) input.current?.focus(); }, [adding]);
  const commit = () => {
    const v = (input.current?.value ?? text).trim().replace(/\s+/g, ' ');
    if (v && !values.some((x) => x.toLowerCase() === v.toLowerCase())) onChange([...values, v]);
    setText('');
  };
  return (
    <div class="em-chips" role="group" aria-labelledby={id + '-l'}>
      {values.map((v) => (
        <span key={v} class="em-chip">
          {v}
          <button type="button" class="em-chip-x" aria-label={t('Remove {name}', { name: v })} onClick={() => onChange(values.filter((x) => x !== v))}>
            <Icon name="close" size={13} stroke={2.6} />
          </button>
        </span>
      ))}
      {adding ? (
        <input
          ref={input}
          id={id}
          type="text"
          class="em-chip-input"
          value={text}
          aria-label={inputLabel}
          onInput={(e) => setText((e.target as HTMLInputElement).value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') { e.preventDefault(); commit(); }
            else if (e.key === 'Backspace' && !text && values.length) onChange(values.slice(0, -1));
          }}
          onBlur={() => { commit(); setAdding(false); }}
        />
      ) : (
        <button ref={addBtn} type="button" class="em-chip-add" onClick={() => setAdding(true)}>
          <Icon name="plus" size={14} stroke={2.6} />
          {addLabel}
        </button>
      )}
    </div>
  );
}

function ratingOptions(editor: MetadataEditorInfo, current: string) {
  const names: string[] = [];
  for (const r of editor.ParentalRatingOptions ?? []) if (r.Name && !names.includes(r.Name)) names.push(r.Name);
  if (current && !names.includes(current)) names.unshift(current);
  return names;
}

function CustomRating({ form, set, editor }: { form: Form; set: Setter; editor: MetadataEditorInfo }) {
  return (
    <ItemField label={t('Custom age rating')} htmlFor="em-custom-rating">
      <ItemSelect id="em-custom-rating" value={form.CustomRating} onChange={(v) => set('CustomRating', v)}>
        <option value="">{t('Same as the age rating')}</option>
        {ratingOptions(editor, form.CustomRating).map((r) => <option key={r} value={r}>{r}</option>)}
      </ItemSelect>
    </ItemField>
  );
}

function DetailsTab({ item, form, set, editor, locked, toggleLock }: { item: BaseItem; form: Form; set: Setter; editor: MetadataEditorInfo } & LockProps) {
  const lock = { locked, toggleLock };
  const ratings = ratingOptions(editor, form.OfficialRating);
  const rating = (
    <ItemField label={t('Age rating')} htmlFor="em-rating" chip={<LockChip field="OfficialRating" name={t('Age rating')} {...lock} />}>
      <ItemSelect id="em-rating" value={form.OfficialRating} onChange={(v) => set('OfficialRating', v)}>
        <option value="">{t('None')}</option>
        {ratings.map((r) => <option key={r} value={r}>{r}</option>)}
      </ItemSelect>
    </ItemField>
  );
  const year = (
    <ItemField label={t('Year')} htmlFor="em-year">
      <Text id="em-year" value={form.ProductionYear} onInput={(v) => set('ProductionYear', v)} inputMode="numeric" placeholder={t('None')} />
    </ItemField>
  );
  let second: ComponentChildren;
  if (item.Type === 'Series') {
    const statuses = form.Status && !STATUSES.includes(form.Status) ? [form.Status, ...STATUSES] : STATUSES;
    second = (
      <>
        <ItemField label={t('First aired')} htmlFor="em-premiere"><DateBox id="em-premiere" value={form.PremiereDate} onInput={(v) => set('PremiereDate', v)} /></ItemField>
        <ItemField label={t('Ended')} htmlFor="em-end"><DateBox id="em-end" value={form.EndDate} onInput={(v) => set('EndDate', v)} placeholder={t('Still airing')} /></ItemField>
        <ItemField label={t('Status')} htmlFor="em-status">
          <ItemSelect id="em-status" value={form.Status} onChange={(v) => set('Status', v)}>
            <option value="">{t('Not set')}</option>
            {statuses.map((s) => <option key={s} value={s}>{statusName(s)}</option>)}
          </ItemSelect>
        </ItemField>
        {rating}
      </>
    );
  } else if (item.Type === 'Episode') {
    second = (
      <>
        <ItemField label={t('Season')} htmlFor="em-season"><Text id="em-season" value={form.ParentIndexNumber} onInput={(v) => set('ParentIndexNumber', v)} inputMode="numeric" /></ItemField>
        <ItemField label={t('Episode')} htmlFor="em-episode"><Text id="em-episode" value={form.IndexNumber} onInput={(v) => set('IndexNumber', v)} inputMode="numeric" /></ItemField>
        <ItemField label={t('Aired')} htmlFor="em-premiere"><DateBox id="em-premiere" value={form.PremiereDate} onInput={(v) => set('PremiereDate', v)} /></ItemField>
        {rating}
      </>
    );
  } else if (item.Type === 'Season') {
    second = (
      <>
        <ItemField label={t('Season number')} htmlFor="em-episode"><Text id="em-episode" value={form.IndexNumber} onInput={(v) => set('IndexNumber', v)} inputMode="numeric" /></ItemField>
        <ItemField label={t('First aired')} htmlFor="em-premiere"><DateBox id="em-premiere" value={form.PremiereDate} onInput={(v) => set('PremiereDate', v)} /></ItemField>
        {year}
        {rating}
      </>
    );
  } else {
    second = (
      <>
        <ItemField label={t('Release date')} htmlFor="em-premiere"><DateBox id="em-premiere" value={form.PremiereDate} onInput={(v) => set('PremiereDate', v)} /></ItemField>
        {year}
        {rating}
        <CustomRating form={form} set={set} editor={editor} />
      </>
    );
  }
  return (
    <>
      <div class="em-grid is-3">
        <ItemField label={t('Title')} htmlFor="em-name" chip={<LockChip field="Name" name={t('Title')} {...lock} />}>
          <Text id="em-name" value={form.Name} onInput={(v) => set('Name', v)} />
        </ItemField>
        <ItemField label={t('Original title')} htmlFor="em-original">
          <Text id="em-original" value={form.OriginalTitle} onInput={(v) => set('OriginalTitle', v)} />
        </ItemField>
        <ItemField label={t('Sort title')} htmlFor="em-sort">
          <Text id="em-sort" value={form.ForcedSortName} onInput={(v) => set('ForcedSortName', v)} placeholder={form.Name} />
        </ItemField>
      </div>
      <div class="em-grid is-4">{second}</div>
      <ItemField label={t('Overview')} htmlFor="em-overview" chip={<LockChip field="Overview" name={t('Overview')} {...lock} />}>
        <textarea id="em-overview" class="im-input" rows={4} value={form.Overview} onInput={(e) => set('Overview', (e.target as HTMLTextAreaElement).value)} />
      </ItemField>
      <div class="em-grid is-2">
        <ItemField label={<span id="em-genres-l">{t('Genres')}</span>} chip={<LockChip field="Genres" name={t('Genres')} {...lock} />}>
          <Chips id="em-genres" values={form.Genres} onChange={(v) => set('Genres', v)} addLabel={t('Add genre')} inputLabel={t('New genre')} />
        </ItemField>
        <ItemField label={<span id="em-tags-l">{t('Tags')}</span>} chip={<LockChip field="Tags" name={t('Tags')} {...lock} />}>
          <Chips id="em-tags" values={form.Tags} onChange={(v) => set('Tags', v)} addLabel={t('Add tag')} inputLabel={t('New tag')} />
        </ItemField>
      </div>
      <div class="em-grid is-2">
        <ItemField
          label={<span id="em-studios-l">{item.Type === 'Series' ? t('Studios and networks') : t('Studios')}</span>}
          chip={<LockChip field="Studios" name={t('Studios')} {...lock} />}
        >
          <Chips id="em-studios" values={form.Studios} onChange={(v) => set('Studios', v)} addLabel={t('Add')} inputLabel={t('New studio')} />
        </ItemField>
        <div class="em-grid is-2">
          <ItemField label={t('Community rating')} htmlFor="em-community">
            <Text id="em-community" value={form.CommunityRating} onInput={(v) => set('CommunityRating', v)} inputMode="decimal" placeholder={t('None')} />
          </ItemField>
          <ItemField label={t('Critic rating')} htmlFor="em-critic">
            <Text id="em-critic" value={form.CriticRating} onInput={(v) => set('CriticRating', v)} inputMode="decimal" placeholder={t('None')} />
          </ItemField>
        </div>
      </div>
    </>
  );
}

function statusName(s: string) {
  if (s === 'Continuing') return t('Continuing');
  if (s === 'Ended') return t('Ended');
  if (s === 'Unreleased') return t('Unreleased');
  return s;
}

function personKind(kind?: string) {
  switch (kind) {
    case 'Actor': return t('Actor');
    case 'GuestStar': return t('Guest star');
    case 'Director': return t('Director');
    case 'Writer': return t('Writer');
    case 'Producer': return t('Producer');
    case 'Creator': return t('Creator');
    case 'Composer': return t('Composer');
    default: return kind ?? '';
  }
}

function PeopleTab({ form, set, locked, toggleLock }: { form: Form; set: Setter } & LockProps) {
  const [name, setName] = useState('');
  const [role, setRole] = useState('');
  const [kind, setKind] = useState('Actor');
  const nameRef = useRef<HTMLInputElement>(null);
  const update = (i: number, patch: Partial<Person>) => set('People', form.People.map((p, n) => (n === i ? { ...p, ...patch } : p)));
  const add = () => {
    const n = name.trim();
    if (!n) { nameRef.current?.focus(); return; }
    // No id yet: the server finds or makes the person on save.
    set('People', [...form.People, { Name: n, Role: role.trim() || undefined, Type: kind } as Person]);
    setName(''); setRole('');
    nameRef.current?.focus();
  };
  const kinds = (current?: string) => (current && !PERSON_KINDS.includes(current) ? [current, ...PERSON_KINDS] : PERSON_KINDS);
  return (
    <>
      <div class="im-label em-section">
        <span>{t('People')}</span>
        <LockChip field="Cast" name={t('Cast and crew')} locked={locked} toggleLock={toggleLock} />
      </div>
      {form.People.length ? (
        <ul class="em-people" aria-label={t('Cast and crew')}>
          {form.People.map((p, i) => {
            const img = p.Id && p.PrimaryImageTag ? personImageUrl({ Id: p.Id, PrimaryImageTag: p.PrimaryImageTag }, 80) : undefined;
            return (
              <li key={i + ':' + p.Name} class="em-person">
                <span class="em-person-who">
                  <span aria-hidden="true" class="em-avatar">{img ? <img src={img} alt="" loading="lazy" /> : p.Name.charAt(0).toUpperCase()}</span>
                  <span class="em-person-name">{p.Name}</span>
                </span>
                <input type="text" class="im-input" value={p.Role ?? ''} placeholder={p.Type === 'Actor' || p.Type === 'GuestStar' ? t('Character') : t('Role')}
                  aria-label={t('Role of {name}', { name: p.Name })} onInput={(e) => update(i, { Role: (e.target as HTMLInputElement).value })} />
                <ItemSelect value={p.Type ?? ''} label={t('What {name} did', { name: p.Name })} onChange={(v) => update(i, { Type: v })}>
                  {kinds(p.Type).map((k) => <option key={k} value={k}>{personKind(k)}</option>)}
                </ItemSelect>
                <button type="button" class="em-icon-btn" aria-label={t('Remove {name}', { name: p.Name })} onClick={() => set('People', form.People.filter((_, n) => n !== i))}>
                  <Icon name="bin" size={16} stroke={2.2} />
                </button>
              </li>
            );
          })}
        </ul>
      ) : (
        <p class="em-note">{t('No cast or crew yet.')}</p>
      )}
      <form class="em-person is-new" onSubmit={(e) => { e.preventDefault(); add(); }}>
        <input ref={nameRef} type="text" class="im-input" value={name} placeholder={t('Name')} aria-label={t('Name of the new person')} onInput={(e) => setName((e.target as HTMLInputElement).value)} />
        <input type="text" class="im-input" value={role} placeholder={kind === 'Actor' || kind === 'GuestStar' ? t('Character') : t('Role')} aria-label={t('Role of the new person')} onInput={(e) => setRole((e.target as HTMLInputElement).value)} />
        <ItemSelect value={kind} label={t('What the new person did')} onChange={setKind}>
          {PERSON_KINDS.map((k) => <option key={k} value={k}>{personKind(k)}</option>)}
        </ItemSelect>
        <button type="submit" class="em-icon-btn is-add" aria-label={t('Add person')}><Icon name="plus" size={16} stroke={2.6} /></button>
      </form>
    </>
  );
}

function IdsTab({ item, form, set, editor }: { item: BaseItem; form: Form; set: Setter; editor: MetadataEditorInfo }) {
  const infos = idsFor(editor.ExternalIdInfos ?? [], item.Type);
  // Ids the item carries that no installed provider claims still show, so saving doesn't drop them unseen.
  const keys = sortIds([...infos.map((i) => i.Key), ...Object.keys(form.ProviderIds).filter((k) => !infos.some((i) => i.Key === k))]);
  return (
    <>
      <div class="em-grid is-3">
        {keys.map((k) => (
          <ItemField key={k} label={t('{name} ID', { name: idName(k) })} htmlFor={'em-id-' + k}>
            <Text id={'em-id-' + k} value={form.ProviderIds[k] ?? ''} placeholder={k === 'Imdb' ? 'tt…' : t('None')}
              onInput={(v) => set('ProviderIds', { ...form.ProviderIds, [k]: v })} />
          </ItemField>
        ))}
      </div>
      <p class="em-note">{t('Changing an ID doesn’t pull new info by itself. Refresh the metadata afterwards, or use Identify.')}</p>
    </>
  );
}

const SERIES_ORDERS = ['', 'originalAirDate', 'absolute', 'dvd', 'digital', 'storyArc', 'production', 'tv', 'alternate', 'regional', 'altdvd'];
const BOXSET_ORDERS = ['PremiereDate', 'SortName'];

function orderName(v: string) {
  switch (v) {
    case '': return t('Aired');
    case 'originalAirDate': return t('Original air date');
    case 'absolute': return t('Absolute');
    case 'dvd': return t('DVD');
    case 'digital': return t('Digital');
    case 'storyArc': return t('Story arc');
    case 'production': return t('Production');
    case 'tv': return t('TV');
    case 'alternate': return t('Alternate');
    case 'regional': return t('Regional');
    case 'altdvd': return t('Alternate DVD');
    case 'PremiereDate': return t('Release date');
    case 'SortName': return t('Name');
    default: return v;
  }
}

function dayName(d: string) {
  const i = DAYS.indexOf(d);
  // 2024-01-01 was a Monday.
  return i < 0 ? d : new Date(2024, 0, 1 + i).toLocaleDateString(locale(), { weekday: 'short' });
}

function DisplayTab({ item, form, set, editor, locked, toggleLock }: { item: BaseItem; form: Form; set: Setter; editor: MetadataEditorInfo } & LockProps) {
  const cultures = useMemo(() => [...(editor.Cultures ?? [])].sort((a, b) => a.DisplayName.localeCompare(b.DisplayName)), [editor]);
  const countries = useMemo(() => [...(editor.Countries ?? [])].sort((a, b) => a.DisplayName.localeCompare(b.DisplayName)), [editor]);
  const orders = item.Type === 'Series' ? SERIES_ORDERS : item.Type === 'BoxSet' ? BOXSET_ORDERS : null;
  const orderList = orders && form.DisplayOrder && !orders.includes(form.DisplayOrder) ? [form.DisplayOrder, ...orders] : orders;
  const langCode = (c: MetadataEditorInfo['Cultures'][number]) => c.TwoLetterISOLanguageName || c.ThreeLetterISOLanguageName;
  // Where Details has no room for it.
  const custom = item.Type === 'Series' || item.Type === 'Season' || item.Type === 'Episode';
  return (
    <>
      <div class={'em-grid ' + (custom ? 'is-3' : 'is-2')}>
        <ItemField label={t('Metadata language')} htmlFor="em-lang">
          <ItemSelect id="em-lang" value={form.PreferredMetadataLanguage} onChange={(v) => set('PreferredMetadataLanguage', v)}>
            <option value="">{t('Library default')}</option>
            {cultures.filter(langCode).map((c) => <option key={c.Name} value={langCode(c)}>{c.DisplayName}</option>)}
          </ItemSelect>
        </ItemField>
        <ItemField label={t('Country')} htmlFor="em-country">
          <ItemSelect id="em-country" value={form.PreferredMetadataCountryCode} onChange={(v) => set('PreferredMetadataCountryCode', v)}>
            <option value="">{t('Library default')}</option>
            {countries.map((c) => <option key={c.Name} value={c.TwoLetterISORegionName}>{c.DisplayName}</option>)}
          </ItemSelect>
        </ItemField>
        {custom && <CustomRating form={form} set={set} editor={editor} />}
      </div>
      {(orderList || item.Type === 'Series') && (
        <div class="em-grid is-3">
          {orderList && (
            <ItemField label={t('Episode order')} htmlFor="em-order">
              <ItemSelect id="em-order" value={form.DisplayOrder} onChange={(v) => set('DisplayOrder', v)}>
                {orderList.map((o) => <option key={o} value={o}>{orderName(o)}</option>)}
              </ItemSelect>
            </ItemField>
          )}
          {item.Type === 'Series' && (
            <>
              <ItemField label={t('Air time')} htmlFor="em-airtime">
                <Text id="em-airtime" value={form.AirTime} onInput={(v) => set('AirTime', v)} placeholder={t('e.g. 9:00 PM')} />
              </ItemField>
              <ItemField label={<span id="em-airdays-l">{t('Air days')}</span>}>
                <div class="em-days" role="group" aria-labelledby="em-airdays-l">
                  {DAYS.map((d) => {
                    const on = form.AirDays.includes(d);
                    return (
                      <button key={d} type="button" class="em-day" aria-pressed={on ? 'true' : 'false'}
                        onClick={() => set('AirDays', on ? form.AirDays.filter((x) => x !== d) : DAYS.filter((x) => x === d || form.AirDays.includes(x)))}>
                        {dayName(d)}
                      </button>
                    );
                  })}
                </div>
              </ItemField>
            </>
          )}
        </div>
      )}
      <ItemField label={t('Tagline')} htmlFor="em-tagline">
        <Text id="em-tagline" value={form.Tagline} onInput={(v) => set('Tagline', v)} placeholder={t('None')} />
      </ItemField>
      <ItemField label={<span id="em-places-l">{t('Production locations')}</span>} chip={<LockChip field="ProductionLocations" name={t('Production locations')} locked={locked} toggleLock={toggleLock} />}>
        <Chips id="em-places" values={form.ProductionLocations} onChange={(v) => set('ProductionLocations', v)} addLabel={t('Add place')} inputLabel={t('New place')} />
      </ItemField>
    </>
  );
}

function Loading() {
  return (
    <div class="em-loading" aria-busy="true" aria-label={t('Loading')}>
      <div class="em-grid is-3">{[0, 1, 2].map((i) => <span key={i} class="em-skel"><span class="skel skel-line" /><span class="skel" /></span>)}</div>
      <div class="em-grid is-4">{[0, 1, 2, 3].map((i) => <span key={i} class="em-skel"><span class="skel skel-line" /><span class="skel" /></span>)}</div>
      <span class="em-skel is-tall"><span class="skel skel-line" /><span class="skel" /></span>
    </div>
  );
}

function LoadFailed({ onRetry }: { onRetry: () => void }) {
  return (
    <div class="em-failed">
      <span>{t('Seedy couldn’t load this item’s metadata.')}</span>
      <ItemButton icon="refresh" onClick={onRetry}>{t('Try again')}</ItemButton>
    </div>
  );
}
