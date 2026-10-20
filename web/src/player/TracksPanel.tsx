import { useEffect, useState } from 'preact/hooks';
import { downloadRemoteSubtitle, searchRemoteSubtitles } from '../api/jellyfin';
import type { MediaStream, RemoteSubtitle } from '../api/types';
import type { SubtitlePrefs } from '../api/seedy';
import { Segmented, Switch } from '../ui/controls';
import { Icon } from '../ui/icons';
import { toast } from '../ui/overlay';
import { formatNumber, getLang, locale, t } from '../lib/i18n';
import { QUALITY_OPTIONS, SPEEDS, type QualityId } from './playback';
import { offsetLabel } from './subtitles';

export type TracksTab = 'subtitles' | 'audio' | 'quality' | 'speed';

/* ── Track names: "English (SDH)" with "SRT · External", "English" with "EAC3 5.1 · Atmos" ── */

let names: Intl.DisplayNames | null = null;
function languageName(code?: string): string {
  if (!code || code === 'und') return '';
  try {
    names ??= new Intl.DisplayNames([getLang() === 'en' ? 'en' : locale()], { type: 'language' });
    const n = names.of(code);
    return n && n.toLowerCase() !== code.toLowerCase() ? n : code.toUpperCase();
  } catch {
    return code.toUpperCase();
  }
}

export function streamLanguage(s: MediaStream): string {
  const localized = (s as MediaStream & { LocalizedLanguage?: string }).LocalizedLanguage;
  // The server names languages in its own language; outside English, the viewer's language comes first.
  const own = capitalize(languageName(s.Language));
  return (getLang() === 'en' ? localized || own : own || localized) || s.Title || (s.Type === 'Audio' ? t('Audio') : t('Subtitles'));
}

/* French language names are lower case ("anglais"); as a track name they start with a capital. */
const capitalize = (s: string) => (s ? s.charAt(0).toLocaleUpperCase(locale()) + s.slice(1) : s);

/* Stream titles that only repeat the language add nothing. */
function extraTitle(s: MediaStream, lang: string): string {
  const t = s.Title?.trim();
  if (!t) return '';
  const low = t.toLowerCase();
  if (low === lang.toLowerCase() || low === (s.Language ?? '').toLowerCase() || languageName(s.Language).toLowerCase() === low) return '';
  if (/^(fran[cç]ais|english|deutsch|espa[nñ]ol|italiano|nederlands|portugu[eê]s|svenska|dansk|norsk|suomi|polski|日本語|中文|한국어)$/i.test(t)) return '';
  if (/^(sdh|cc|forced|default|full|subtitles?|audio)$/i.test(t)) return '';
  return t;
}

const SUB_CODECS: Record<string, string> = {
  subrip: 'SRT', srt: 'SRT', ass: 'ASS', ssa: 'SSA', webvtt: 'VTT', vtt: 'VTT', mov_text: 'TX3G', tx3g: 'TX3G',
  pgssub: 'PGS', pgs: 'PGS', dvdsub: 'VobSub', vobsub: 'VobSub', dvbsub: 'DVB', ttml: 'TTML', smi: 'SAMI', sami: 'SAMI', microdvd: 'SUB'
};

export function subtitleTitle(s: MediaStream): string {
  const lang = streamLanguage(s);
  const extra = extraTitle(s, lang);
  const base = s.IsHearingImpaired ? t('{lang} (SDH)', { lang }) : lang;
  return extra ? `${base} · ${extra}` : base;
}

export function subtitleSub(s: MediaStream): string {
  const codec = SUB_CODECS[(s.Codec ?? '').toLowerCase()] ?? (s.Codec ?? '').toUpperCase();
  const where = s.IsForced ? t('Forced') : s.IsExternal ? t('External') : t('Embedded');
  return [codec, where].filter(Boolean).join(' · ');
}

const AUDIO_CODECS: Record<string, string> = {
  aac: 'AAC', ac3: 'AC3', eac3: 'EAC3', truehd: 'TrueHD', dts: 'DTS', flac: 'FLAC', opus: 'Opus', mp3: 'MP3', vorbis: 'Vorbis', alac: 'ALAC', mp2: 'MP2'
};

export function channelsLabel(n?: number, layout?: string): string {
  if (!n) return layout ?? '';
  if (n >= 8) return '7.1';
  if (n === 7) return '6.1';
  if (n === 6) return '5.1';
  if (n === 2) return t('Stereo');
  if (n === 1) return t('Mono');
  return t('{n} ch', { n });
}

export function audioTitle(s: MediaStream): string {
  const lang = streamLanguage(s);
  const extra = extraTitle(s, lang);
  return extra ? `${lang} · ${extra}` : lang;
}

export function audioSub(s: MediaStream): string {
  const c = (s.Codec ?? '').toLowerCase();
  let codec = AUDIO_CODECS[c] ?? (c.startsWith('pcm') ? 'PCM' : c.toUpperCase());
  if (c === 'dts' && s.Profile && /MA|HD|X/.test(s.Profile)) codec = s.Profile.replace(/^DTS[- ]?/i, 'DTS-').replace('DTS-HD MA', 'DTS-HD MA');
  const spatial = s.AudioSpatialFormat === 'DolbyAtmos' || /atmos/i.test(s.Profile ?? '') ? 'Atmos' : s.AudioSpatialFormat === 'DTSX' ? 'DTS:X' : '';
  return [[codec, channelsLabel(s.Channels, s.ChannelLayout)].filter(Boolean).join(' '), spatial].filter(Boolean).join(' · ');
}

export const speedLabel = (s: number) => (s === 1 ? t('Normal') : `${formatNumber(s)}×`);

/* Under "Auto": "Up to 8 Mbps · currently 1080p", or "Currently 1080p". */
function autoSub(limit?: string, current?: string): string | undefined {
  if (limit && current) return t('Up to {limit} · currently {current}', { limit, current });
  if (limit) return t('Up to {limit}', { limit });
  if (current) return t('Currently {current}', { current });
  return undefined;
}

/* ── Panel rows ── */

function Choice({ label, sub, on, onSelect, disabled }: { label: string; sub?: string; on: boolean; onSelect: () => void; disabled?: boolean }) {
  return (
    <button type="button" class="menu-item pl-choice" aria-pressed={on ? 'true' : 'false'} onClick={onSelect} disabled={disabled}>
      {sub ? <span class="two"><span>{label}</span><span class="sub">{sub}</span></span> : <span>{label}</span>}
      {on && <Icon name="check" size={20} stroke={2.6} class="tick" />}
    </button>
  );
}

export interface TracksPanelProps {
  tab: TracksTab;
  onTab: (t: TracksTab) => void;
  itemId: string;
  subtitles: MediaStream[];
  subtitleIndex: number;
  onSubtitle: (index: number) => void;
  audio: MediaStream[];
  audioIndex?: number;
  onAudio: (index: number) => void;
  quality: QualityId;
  currentQuality: string;
  /* This device's limit behind "Auto" (Settings · Playback), when it is below the top rate. */
  autoLimit?: string;
  onQuality: (q: QualityId) => void;
  speed: number;
  onSpeed: (s: number) => void;
  subPrefs: SubtitlePrefs;
  onSubPrefs: (p: SubtitlePrefs) => void;
  offset: number;
  onOffset: (v: number) => void;
  canFindSubtitles: boolean;
  searchLanguage: string;
  onDownloaded: () => Promise<void> | void;
  busy?: boolean;
}

export function TracksPanel(p: TracksPanelProps) {
  const [finding, setFinding] = useState(false);
  useEffect(() => { if (p.tab !== 'subtitles') setFinding(false); }, [p.tab]);

  return (
    <section class="pl-panel" aria-label={t(p.tab === 'subtitles' ? 'Subtitle settings' : p.tab === 'audio' ? 'Audio settings' : p.tab === 'quality' ? 'Quality settings' : 'Playback speed')}>
      <Segmented<TracksTab>
        role="tablist"
        variant="fill"
        label={t('Playback menus')}
        value={p.tab}
        onChange={p.onTab}
        options={[
          { value: 'subtitles', label: t('Subtitles') },
          { value: 'audio', label: t('Audio') },
          { value: 'quality', label: t('Quality') },
          { value: 'speed', label: t('Speed') }
        ]}
      />

      {p.tab === 'subtitles' && !finding && (
        <>
          <div class="pl-group" role="group" aria-label={t('Subtitle track')}>
            <span class="pl-label">{t('Track')}</span>
            <Choice label={t('Off')} on={p.subtitleIndex < 0} onSelect={() => p.onSubtitle(-1)} />
            {p.subtitles.map((s) => (
              <Choice key={s.Index} label={subtitleTitle(s)} sub={subtitleSub(s)} on={p.subtitleIndex === s.Index} onSelect={() => p.onSubtitle(s.Index)} />
            ))}
            {p.canFindSubtitles && (
              <button type="button" class="menu-item pl-find" onClick={() => setFinding(true)}>
                <Icon name="search" size={18} />
                {t('Find more subtitles')}
              </button>
            )}
          </div>
          <div aria-hidden="true" class="pl-divider" />
          <div class="pl-group is-appearance">
            <span class="pl-label">{t('Appearance')}</span>
            <div class="pl-setting">
              <span>{t('Size')}</span>
              <Segmented<'S' | 'M' | 'L'>
                variant="small"
                label={t('Subtitle size')}
                value={p.subPrefs.size}
                onChange={(size) => p.onSubPrefs({ ...p.subPrefs, size })}
                options={[{ value: 'S', label: 'S', ariaLabel: t('Small') }, { value: 'M', label: 'M', ariaLabel: t('Medium') }, { value: 'L', label: 'L', ariaLabel: t('Large') }]}
              />
            </div>
            <div class="pl-setting">
              <span>{t('Lift above controls')}</span>
              <Switch checked={p.subPrefs.lift} label={t('Lift subtitles above controls')} onChange={(lift) => p.onSubPrefs({ ...p.subPrefs, lift })} />
            </div>
            <div class="pl-setting">
              <span>{t('Sync offset')}</span>
              <div class="pl-offset">
                <button type="button" class="pl-step" aria-label={t('Show subtitles earlier')} onClick={() => p.onOffset(Math.round((p.offset - 0.1) * 10) / 10)}>
                  <Icon name="minus" size={14} />
                </button>
                <span class="pl-offset-value" aria-live="polite">{offsetLabel(p.offset)}</span>
                <button type="button" class="pl-step" aria-label={t('Show subtitles later')} onClick={() => p.onOffset(Math.round((p.offset + 0.1) * 10) / 10)}>
                  <Icon name="plus" size={14} />
                </button>
              </div>
            </div>
          </div>
        </>
      )}

      {p.tab === 'subtitles' && finding && (
        <FindSubtitles itemId={p.itemId} language={p.searchLanguage} onBack={() => setFinding(false)} onDownloaded={async () => { await p.onDownloaded(); setFinding(false); }} />
      )}

      {p.tab === 'audio' && (
        <div class="pl-group" role="group" aria-label={t('Audio track')}>
          <span class="pl-label">{t('Track')}</span>
          {p.audio.map((s) => (
            <Choice key={s.Index} label={audioTitle(s)} sub={audioSub(s)} on={p.audioIndex === s.Index} onSelect={() => p.onAudio(s.Index)} disabled={p.busy} />
          ))}
          {!p.audio.length && <span class="menu-item is-muted">{t('This title has no audio tracks')}</span>}
        </div>
      )}

      {p.tab === 'quality' && (
        <div class="pl-group" role="group" aria-label={t('Quality')}>
          <span class="pl-label">{t('Quality')}</span>
          {QUALITY_OPTIONS.map((q) => (
            <Choice
              key={q.id}
              label={q.id === 'auto' ? t(q.label) : q.label}
              sub={q.id === 'auto' ? autoSub(p.autoLimit, p.currentQuality) : undefined}
              on={p.quality === q.id}
              onSelect={() => p.onQuality(q.id)}
              disabled={p.busy}
            />
          ))}
        </div>
      )}

      {p.tab === 'speed' && (
        <div class="pl-group" role="group" aria-label={t('Playback speed')}>
          <span class="pl-label">{t('Speed')}</span>
          {SPEEDS.map((s) => <Choice key={s} label={speedLabel(s)} on={p.speed === s} onSelect={() => p.onSpeed(s)} />)}
        </div>
      )}
    </section>
  );
}

function FindSubtitles({ itemId, language, onBack, onDownloaded }: { itemId: string; language: string; onBack: () => void; onDownloaded: () => Promise<void> }) {
  const [state, setState] = useState<{ status: 'loading' | 'done' | 'error'; results: RemoteSubtitle[] }>({ status: 'loading', results: [] });
  const [getting, setGetting] = useState<string | null>(null);
  const langName = languageName(language) || language;

  useEffect(() => {
    let alive = true;
    searchRemoteSubtitles(itemId, language).then(
      (results) => alive && setState({ status: 'done', results: results ?? [] }),
      () => alive && setState({ status: 'error', results: [] })
    );
    return () => { alive = false; };
  }, [itemId, language]);

  const pick = async (r: RemoteSubtitle) => {
    setGetting(r.Id);
    try {
      await downloadRemoteSubtitle(itemId, r.Id);
      toast(t('Subtitles added'));
      await onDownloaded();
    } catch {
      toast(t("Couldn't download those subtitles"), 'error');
    } finally {
      setGetting(null);
    }
  };

  return (
    <div class="pl-group" role="group" aria-label={t('Find subtitles')}>
      <button type="button" class="menu-item pl-find" onClick={onBack}>
        <Icon name="chevronLeft" size={18} />
        {t('Back to tracks')}
      </button>
      <span class="pl-label">{t('{lang} subtitles', { lang: langName })}</span>
      {state.status === 'loading' && <span class="menu-item is-muted" aria-busy="true"><span class="dot is-pending" />{t('Searching…')}</span>}
      {state.status === 'error' && <span class="menu-item is-muted"><span class="dot is-danger" />{t('Search failed. Check the subtitle providers.')}</span>}
      {state.status === 'done' && !state.results.length && <span class="menu-item is-muted">{t('No {lang} subtitles found', { lang: langName })}</span>}
      {state.results.slice(0, 30).map((r) => (
        <button key={r.Id} type="button" class="menu-item pl-choice" disabled={!!getting} onClick={() => pick(r)}>
          <span class="two">
            <span>{r.Name || r.Id}</span>
            <span class="sub">
              {[r.ProviderName, r.Format?.toUpperCase(), r.DownloadCount ? t('{n} downloads', { n: r.DownloadCount }) : '', r.HearingImpaired ? t('SDH') : '', r.Forced ? t('Forced') : '']
                .filter(Boolean).join(' · ')}
            </span>
          </span>
          {getting === r.Id ? <span class="dot is-pending" /> : <Icon name="download" size={18} class="pl-find-dl" />}
        </button>
      ))}
    </div>
  );
}
