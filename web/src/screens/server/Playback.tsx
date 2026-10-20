import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { t, tn, tx } from '../../lib/i18n';
import { href, setQuery, useLocation } from '../../lib/router';
import { toast } from '../../ui/overlay';
import { AdminLayout, PanelSkeletons } from '../admin/AdminLayout';
import { type EncodingOptions, errText, ffmpegVersion, getEncoding, getServerConfig, getSessions, getSystemInfo, type GpuScan, type HwAccel, saveEncoding, saveServerConfig, scanGpus, type ServerConfig, type TrickplayOptions } from './api';
import { ChipList, LoadFailed, NumBox, PathField, Select, SettingRow, SwitchCtl, tOpts, UnsavedBar, useDraft, useLoad, useUnsavedGuard } from './parts';
;

type Tab = 'transcoding' | 'resume' | 'streaming' | 'previews';
const TABS: { id: Tab; label: string }[] = [
  { id: 'transcoding', label: 'Transcoding' },
  { id: 'resume', label: 'Resume' },
  { id: 'streaming', label: 'Streaming' },
  { id: 'previews', label: 'Seek previews' }
];

/* ── Hardware acceleration ── */

const ACCELS: { id: HwAccel; name: string; sub: string; short: string }[] = [
  { id: 'none', name: 'None', sub: 'CPU only', short: '' },
  { id: 'qsv', name: 'Intel Quick Sync', sub: 'Most Intel graphics', short: 'Quick Sync' },
  { id: 'nvenc', name: 'NVIDIA NVENC', sub: 'GeForce, Quadro, RTX', short: 'NVENC' },
  { id: 'amf', name: 'AMD AMF', sub: 'Radeon, Windows and Linux', short: 'AMF' },
  { id: 'vaapi', name: 'VA-API', sub: 'Intel and AMD on Linux', short: 'VA-API' },
  { id: 'videotoolbox', name: 'Apple VideoToolbox', sub: 'Macs with Apple chips', short: 'VideoToolbox' },
  { id: 'rkmpp', name: 'Rockchip RKMPP', sub: 'Rockchip boards', short: 'RKMPP' }
];
const V4L2 = { id: 'v4l2m2m' as HwAccel, name: 'Video4Linux2', sub: 'Raspberry Pi and other ARM boards', short: 'V4L2' };

/* The one option each detected GPU is best served by. */
function detected(scan: GpuScan | undefined): Set<HwAccel> {
  const s = new Set<HwAccel>();
  if (!scan) return s;
  if (scan.intel) s.add('qsv');
  else if (scan.amd || (scan.renderNodes.length > 0 && !scan.nvidia && !scan.rockchip)) s.add('vaapi');
  if (scan.nvidia) s.add('nvenc');
  if (scan.rockchip) s.add('rkmpp');
  if (scan.mac) s.add('videotoolbox');
  return s;
}

/* Codecs each acceleration can decode, as Jellyfin's own settings page offers them. */
type CodecKey = { codec: string } | { flag: keyof EncodingOptions };
const ALL = ['amf', 'nvenc', 'qsv', 'vaapi', 'rkmpp', 'videotoolbox', 'v4l2m2m'];
const TEN_BIT = ['amf', 'nvenc', 'qsv', 'vaapi', 'rkmpp'];
const REXT = ['nvenc', 'qsv', 'vaapi'];
const CODECS: { name: string; key: CodecKey; types: string[] }[] = [
  { name: 'H.264', key: { codec: 'h264' }, types: ALL },
  { name: 'HEVC', key: { codec: 'hevc' }, types: ['amf', 'nvenc', 'qsv', 'vaapi', 'rkmpp', 'videotoolbox'] },
  { name: 'MPEG-1', key: { codec: 'mpeg1video' }, types: ['rkmpp'] },
  { name: 'MPEG-2', key: { codec: 'mpeg2video' }, types: ['amf', 'nvenc', 'qsv', 'vaapi', 'rkmpp'] },
  { name: 'MPEG-4', key: { codec: 'mpeg4' }, types: ['nvenc', 'rkmpp'] },
  { name: 'VC-1', key: { codec: 'vc1' }, types: ['amf', 'nvenc', 'qsv', 'vaapi'] },
  { name: 'VP8', key: { codec: 'vp8' }, types: ['nvenc', 'qsv', 'vaapi', 'rkmpp', 'videotoolbox'] },
  { name: 'VP9', key: { codec: 'vp9' }, types: ['amf', 'nvenc', 'qsv', 'vaapi', 'rkmpp', 'videotoolbox'] },
  { name: 'AV1', key: { codec: 'av1' }, types: ['amf', 'nvenc', 'qsv', 'vaapi', 'rkmpp', 'videotoolbox'] },
  { name: 'HEVC 10-bit', key: { flag: 'EnableDecodingColorDepth10Hevc' }, types: TEN_BIT },
  { name: 'VP9 10-bit', key: { flag: 'EnableDecodingColorDepth10Vp9' }, types: TEN_BIT },
  { name: 'HEVC RExt 8/10-bit', key: { flag: 'EnableDecodingColorDepth10HevcRext' }, types: REXT },
  { name: 'HEVC RExt 12-bit', key: { flag: 'EnableDecodingColorDepth12HevcRext' }, types: REXT }
];

const PRESETS: { value: EncodingOptions['EncoderPreset']; label: string }[] = [
  { value: 'auto', label: 'Auto' },
  { value: 'veryslow', label: 'Very slow' },
  { value: 'slower', label: 'Slower' },
  { value: 'slow', label: 'Slow' },
  { value: 'medium', label: 'Medium' },
  { value: 'fast', label: 'Fast' },
  { value: 'faster', label: 'Faster' },
  { value: 'veryfast', label: 'Very fast' },
  { value: 'superfast', label: 'Super fast' },
  { value: 'ultrafast', label: 'Ultra fast' }
];

const ALGORITHMS: { value: EncodingOptions['TonemappingAlgorithm']; label: string }[] = [
  { value: 'bt2390', label: 'BT.2390' },
  { value: 'hable', label: 'Hable' },
  { value: 'mobius', label: 'Möbius' },
  { value: 'reinhard', label: 'Reinhard' },
  { value: 'gamma', label: 'Gamma' },
  { value: 'linear', label: 'Linear' },
  { value: 'clip', label: 'Clip' },
  { value: 'none', label: 'None' }
];

/* Built at render: the labels are translated. */
const threadsLabel = (n: number) => tn(n, '{n} thread', '{n} threads');
const threadOptions = () => [
  { value: -1, label: t('Auto') },
  { value: 0, label: t('As many as possible') },
  ...Array.from({ length: 16 }, (_, i) => ({ value: i + 1, label: threadsLabel(i + 1) }))
];

/* Only these labels are words; the rest are names. */
const WORD_ALGORITHMS = new Set<string>(['linear', 'clip', 'none']);
const WORD_DOWNMIX = new Set(['None', 'NightmodeDialogue']);

const DOWNMIX = [
  { value: 'None', label: 'Standard' },
  { value: 'Dave750', label: 'Dave750' },
  { value: 'NightmodeDialogue', label: 'Night mode dialogue' },
  { value: 'Rfc7845', label: 'RFC 7845' },
  { value: 'Ac4', label: 'AC-4' }
];

const DEINTERLACE = [
  { value: 'yadif', label: 'YADIF' },
  { value: 'bwdif', label: 'BWDIF' }
];

const PRIORITY: { value: TrickplayOptions['ProcessPriority']; label: string }[] = [
  { value: 'Idle', label: 'Lowest' },
  { value: 'BelowNormal', label: 'Below normal' },
  { value: 'Normal', label: 'Normal' },
  { value: 'AboveNormal', label: 'Above normal' },
  { value: 'High', label: 'High' }
];

/* ── Page ── */

export function ServerPlayback() {
  const loc = useLocation();
  const q = loc.query.get('tab');
  const tab: Tab = TABS.some((x) => x.id === q) ? (q as Tab) : 'transcoding';

  const enc = useLoad(getEncoding);
  const srv = useLoad(getServerConfig);
  const e = useDraft<EncodingOptions>(enc.data);
  const s = useDraft<ServerConfig>(srv.data);
  const changes = e.changes + s.changes;
  const [saving, setSaving] = useState(false);
  useUnsavedGuard(changes > 0, t('Your changes to the playback settings haven’t been saved yet. If you leave now, they’re lost.'));

  const save = async () => {
    setSaving(true);
    try {
      if (e.draft && e.changes && e.value) {
        /* Jellyfin replaces the whole encoding configuration: save on top of what the server has now. */
        const fresh = await getEncoding();
        const next = { ...fresh, ...pickChanged(e.value, enc.data!) } as EncodingOptions;
        await saveEncoding(next);
        enc.setData(next);
        e.reset();
      }
      if (s.draft && s.changes && s.value) {
        /* The server configuration is shared with other pages: save on top of what the server has now. */
        const fresh = await getServerConfig();
        const next = { ...fresh, ...pickChanged(s.value, srv.data!) } as ServerConfig;
        /* Seek previews are one nested object: merge it field by field too. */
        if (fresh.TrickplayOptions && srv.data!.TrickplayOptions && s.value.TrickplayOptions)
          next.TrickplayOptions = { ...fresh.TrickplayOptions, ...pickChanged(s.value.TrickplayOptions, srv.data!.TrickplayOptions) };
        await saveServerConfig(next);
        srv.setData(next);
        s.reset();
      }
      toast(t('Playback settings saved'));
    } catch (err) {
      toast(errText(err, t('Couldn’t save the playback settings.')), 'error');
    } finally {
      setSaving(false);
    }
  };

  const loading = enc.loading && !enc.data || srv.loading && !srv.data;
  const error = enc.error ?? srv.error;

  return (
    <AdminLayout
      page="playback"
      wide
      title={t('Playback')}
      lede={t('How the server converts video, when progress is saved, remote streaming limits and seek previews.')}
    >
      <span class="srv-w880" hidden />
      <nav aria-label={t('Playback settings')} class="tab-row hscroll">
        {TABS.map((x) => (
          <a
            key={x.id}
            class="tab"
            href={href('/admin/playback' + (x.id === 'transcoding' ? '' : '?tab=' + x.id))}
            aria-current={x.id === tab ? 'page' : undefined}
            onClick={(ev) => {
              if (ev.button !== 0 || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return;
              ev.preventDefault();
              setQuery({ tab: x.id === 'transcoding' ? null : x.id });
            }}
          >
            {t(x.label)}
          </a>
        ))}
      </nav>

      {error && !loading ? (
        <LoadFailed error={error} title={t('Couldn’t load the playback settings')} onRetry={() => { enc.reload(); srv.reload(); }} />
      ) : loading || !e.value || !s.value ? (
        <PanelSkeletons count={3} rows={4} />
      ) : (
        <>
          {tab === 'transcoding' && <Transcoding o={e.value} set={e.patch} />}
          {tab === 'resume' && <Resume c={s.value} set={s.patch} />}
          {tab === 'streaming' && <Streaming c={s.value} setC={s.patch} o={e.value} setO={e.patch} />}
          {tab === 'previews' && <Previews t={s.value.TrickplayOptions} set={(p) => s.patch({ TrickplayOptions: { ...s.value!.TrickplayOptions, ...p } })} />}
        </>
      )}

      <UnsavedBar
        changes={changes}
        note={t('applies to new streams')}
        saving={saving}
        onDiscard={() => { e.reset(); s.reset(); }}
        onSave={() => void save()}
      />
    </AdminLayout>
  );
}

/* Only the fields this page changed, so saving doesn't undo edits made elsewhere since it loaded. */
function pickChanged<T extends Record<string, unknown>>(draft: T, saved: T): Partial<T> {
  const out: Partial<T> = {};
  for (const k of Object.keys(draft) as (keyof T)[]) {
    if (JSON.stringify(draft[k]) !== JSON.stringify(saved[k])) (out as Record<string, unknown>)[k as string] = draft[k];
  }
  return out;
}

function Group({ title, children }: { title: string; children: ComponentChildren }) {
  return (
    <section class="panel" aria-label={title}>
      <h2 class="panel-title srv-panel-h">{title}</h2>
      {children}
    </section>
  );
}

const Sw = ({ name, help, on, set }: { name: string; help: string; on: boolean; set: (v: boolean) => void }) => (
  <SettingRow name={name} help={help}><SwitchCtl on={on} set={set} label={name} /></SettingRow>
);

/* ── Transcoding ── */

function Transcoding({ o, set }: { o: EncodingOptions; set: (p: Partial<EncodingOptions>) => void }) {
  const [version, setVersion] = useState<string | null | undefined>(undefined);
  const [running, setRunning] = useState<number | null>(null);
  const [scan, setScan] = useState<GpuScan | undefined>(undefined);

  useEffect(() => {
    let alive = true;
    ffmpegVersion().then((v) => { if (alive) setVersion(v); }, () => { if (alive) setVersion(null); });
    const count = () => getSessions().then(
      (list) => { if (alive) setRunning(list.filter((x) => x.NowPlayingItem && x.PlayState?.PlayMethod === 'Transcode').length); },
      () => undefined
    );
    void count();
    const timer = window.setInterval(count, 15_000);
    getSystemInfo().then((i) => i.ProgramDataPath, () => undefined).then((p) => scanGpus(p)).then((g) => { if (alive) setScan(g); }, () => undefined);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  const hw = o.HardwareAccelerationType;
  const found = detected(scan);
  const accels = hw === 'v4l2m2m' ? [...ACCELS, V4L2] : ACCELS;
  const intel = hw === 'qsv' || hw === 'vaapi';
  const path = o.EncoderAppPathDisplay || o.EncoderAppPath || '';

  const deviceKey = hw === 'qsv' ? 'QsvDevice' : 'VaapiDevice';
  const deviceValue = (hw === 'qsv' ? o.QsvDevice : o.VaapiDevice) ?? '';
  const nodes = scan?.renderNodes.length ? scan.renderNodes : ['/dev/dri/renderD128', '/dev/dri/renderD129'];
  const deviceOptions = [
    ...(hw === 'qsv' ? [{ value: '', label: t('Automatic') }] : []),
    ...[...new Set([...(deviceValue ? [deviceValue] : []), ...nodes])].map((n) => ({ value: n, label: n }))
  ];

  const codecs = CODECS.filter((c) => c.types.includes(hw));
  const isOn = (k: CodecKey) => ('codec' in k ? o.HardwareDecodingCodecs.includes(k.codec) : !!o[k.flag]);
  const toggle = (k: CodecKey, on: boolean) => {
    if ('codec' in k) {
      const rest = o.HardwareDecodingCodecs.filter((c) => c !== k.codec);
      set({ HardwareDecodingCodecs: on ? [...rest, k.codec] : rest });
    } else set({ [k.flag]: on } as Partial<EncodingOptions>);
  };
  const shortName = accels.find((a) => a.id === hw)?.short ?? '';
  const threads = threadOptions();

  return (
    <>
      <div class="srv-ffmpeg" role="status">
        <span class={'dot' + (path ? '' : ' is-danger')} />
        {path ? (
          <span class="srv-ffmpeg-text">{tx('{ffmpeg} at {path}', { ffmpeg: <b>FFmpeg{version ? ' ' + version : ''}</b>, path: <span class="srv-mono-plain">{path}</span> })}</span>
        ) : (
          <span class="srv-ffmpeg-text"><b>{t('FFmpeg wasn’t found.')}</b> {t('Nothing can be converted until the server finds it.')}</span>
        )}
        {running !== null && <span class="srv-ffmpeg-side">{running ? tn(running, '{n} transcode running', '{n} transcodes running') : t('No transcodes running')}</span>}
      </div>

      <section aria-labelledby="hw-h" class="panel srv-hw">
        <div class="srv-head">
          <h2 id="hw-h" class="panel-title">{t('Hardware acceleration')}</h2>
          <p class="panel-text">{t('Uses the GPU for converting video. Much faster than the CPU, especially for HDR.')}</p>
        </div>
        <div role="radiogroup" aria-label={t('Hardware acceleration')} class="srv-accels">
          {accels.map((a) => {
            const isFound = found.has(a.id);
            const name = a.id === 'none' ? t(a.name) : a.name;
            return (
              <label key={a.id} class={'srv-accel' + (hw === a.id ? ' is-on' : '')}>
                <input type="radio" name="hw" checked={hw === a.id} aria-label={name} onChange={() => set({ HardwareAccelerationType: a.id })} />
                <span class="srv-accel-body">
                  <span class="srv-accel-name">{name}</span>
                  <span class={'srv-accel-sub' + (isFound ? ' is-found' : '')}>{isFound ? t('Detected on this server') : t(a.sub)}</span>
                </span>
              </label>
            );
          })}
        </div>
        {intel && (
          <div class="srv-hw-row">
            <label for="hw-dev" class="srv-label">
              <span class="srv-name">{t('Device')}</span>
              <span class="srv-help">{t('The GPU {name} runs on', { name: shortName })}</span>
            </label>
            <Select id="hw-dev" mono minWidth={260} value={deviceValue} options={deviceOptions}
              onChange={(v) => set({ [deviceKey]: v } as Partial<EncodingOptions>)} />
          </div>
        )}
        {hw !== 'none' && codecs.length > 0 && (
          <div class="srv-hw-col" role="group" aria-labelledby="dec-h">
            <span class="srv-label">
              <span id="dec-h" class="srv-name">{t('Decode on the GPU')}</span>
              <span class="srv-help">{t('Formats your GPU can read. Unchecked ones fall back to the CPU')}</span>
            </span>
            <div class="srv-checks">
              {codecs.map((c) => (
                <label key={c.name} class="srv-check-sm">
                  <input type="checkbox" checked={isOn(c.key)} onChange={(ev) => toggle(c.key, (ev.currentTarget as HTMLInputElement).checked)} />
                  {c.name}
                </label>
              ))}
            </div>
          </div>
        )}
      </section>

      <Group title={t('Encoding')}>
        {hw !== 'none' && <Sw name={t('Encode on the GPU')} help={t('Turn off to decode on the GPU but encode on the CPU')} on={o.EnableHardwareEncoding} set={(v) => set({ EnableHardwareEncoding: v })} />}
        <Sw name={t('Allow HEVC output')} help={t('Smaller streams for devices that can play HEVC')} on={o.AllowHevcEncoding} set={(v) => set({ AllowHevcEncoding: v })} />
        <Sw name={t('Allow AV1 output')} help={t('Smallest streams; needs a recent GPU and device')} on={o.AllowAv1Encoding} set={(v) => set({ AllowAv1Encoding: v })} />
        {intel && (
          <Sw name={t('Intel low-power encoders')} help={t('Faster on recent Intel GPUs')}
            on={o.EnableIntelLowPowerH264HwEncoder && o.EnableIntelLowPowerHevcHwEncoder}
            set={(v) => set({ EnableIntelLowPowerH264HwEncoder: v, EnableIntelLowPowerHevcHwEncoder: v })} />
        )}
        <SettingRow name={t('Encoder preset')} help={t('Trades speed for quality')}>
          <Select label={t('Encoder preset')} value={o.EncoderPreset} options={tOpts(PRESETS)} onChange={(v) => set({ EncoderPreset: v })} />
        </SettingRow>
        <SettingRow name={t('H.264 quality (CRF)')} help={t('Lower is better quality and bigger files')}>
          <NumBox label={t('H.264 quality (CRF)')} value={o.H264Crf} min={0} max={51} onChange={(v) => set({ H264Crf: v })} />
        </SettingRow>
        <SettingRow name={t('HEVC quality (CRF)')} help={t('Lower is better quality and bigger files')}>
          <NumBox label={t('HEVC quality (CRF)')} value={o.H265Crf} min={0} max={51} onChange={(v) => set({ H265Crf: v })} />
        </SettingRow>
      </Group>

      <Group title={t('HDR to SDR')}>
        <Sw name={t('Tone mapping')} help={t('Keeps HDR video from looking washed out on SDR screens')} on={o.EnableTonemapping} set={(v) => set({ EnableTonemapping: v })} />
        <SettingRow name={t('Algorithm')} help={t('How bright highlights are compressed')}>
          <Select label={t('Algorithm')} value={o.TonemappingAlgorithm} options={ALGORITHMS.map((a) => (WORD_ALGORITHMS.has(a.value) ? { ...a, label: t(a.label) } : a))} onChange={(v) => set({ TonemappingAlgorithm: v })} />
        </SettingRow>
        {intel && <Sw name={t('Intel VPP tone mapping')} help={t('Uses Intel’s built-in tone mapping instead')} on={o.EnableVppTonemapping} set={(v) => set({ EnableVppTonemapping: v })} />}
        {hw === 'videotoolbox' && <Sw name={t('VideoToolbox tone mapping')} help={t('Uses Apple’s built-in tone mapping instead')} on={o.EnableVideoToolboxTonemapping} set={(v) => set({ EnableVideoToolboxTonemapping: v })} />}
      </Group>

      <Group title={t('Resources')}>
        <SettingRow name={t('Encoding threads')} help={t('Leave on Auto unless the server runs other heavy work')}>
          <Select label={t('Encoding threads')} value={o.EncodingThreadCount} options={threads.some((x) => x.value === o.EncodingThreadCount) ? threads : [...threads, { value: o.EncodingThreadCount, label: threadsLabel(o.EncodingThreadCount) }]}
            onChange={(v) => set({ EncodingThreadCount: v })} />
        </SettingRow>
        <Sw name={t('Throttle transcodes')} help={t('Pauses converting when far enough ahead of the viewer')} on={o.EnableThrottling} set={(v) => set({ EnableThrottling: v })} />
        <SettingRow name={t('Start throttling after')} help={t('Seconds converted ahead of playback')}>
          <NumBox label={t('Start throttling after, in seconds')} unit="s" value={o.ThrottleDelaySeconds} min={60} max={3600} onChange={(v) => set({ ThrottleDelaySeconds: v })} />
        </SettingRow>
        <Sw name={t('Delete old segments')} help={t('Frees disk space during long streams')} on={o.EnableSegmentDeletion} set={(v) => set({ EnableSegmentDeletion: v })} />
        <SettingRow name={t('Keep segments for')} help={t('Seconds of already-watched video kept')}>
          <NumBox label={t('Keep segments for, in seconds')} unit="s" value={o.SegmentKeepSeconds} min={120} max={3600} onChange={(v) => set({ SegmentKeepSeconds: v })} />
        </SettingRow>
        <Sw name={t('Extract subtitles on the fly')} help={t('For subtitles burned in or converted while streaming')} on={o.EnableSubtitleExtraction} set={(v) => set({ EnableSubtitleExtraction: v })} />
      </Group>
    </>
  );
}

/* ── Resume ── */

function Resume({ c, set }: { c: ServerConfig; set: (p: Partial<ServerConfig>) => void }) {
  return (
    <>
      <Group title={t('Video')}>
        <SettingRow name={t('Counts as started after')} help={t('Stopping earlier keeps no resume point')}>
          <NumBox label={t('Counts as started after, in percent')} unit="%" value={c.MinResumePct} min={0} max={Math.max(0, c.MaxResumePct - 1)} onChange={(v) => set({ MinResumePct: v })} />
        </SettingRow>
        <SettingRow name={t('Counts as watched after')} help={t('Stopping later marks the video as watched')}>
          <NumBox label={t('Counts as watched after, in percent')} unit="%" value={c.MaxResumePct} min={Math.min(100, c.MinResumePct + 1)} max={100} onChange={(v) => set({ MaxResumePct: v })} />
        </SettingRow>
        <SettingRow name={t('Shortest video with a resume point')} help={t('Shorter videos always start from the beginning')}>
          <NumBox label={t('Shortest video with a resume point, in seconds')} unit="s" value={c.MinResumeDurationSeconds} min={0} max={86400} onChange={(v) => set({ MinResumeDurationSeconds: v })} />
        </SettingRow>
      </Group>
      <Group title={t('Audiobooks')}>
        <SettingRow name={t('Counts as started after')} help={t('Stopping in the first minutes starts the book over')}>
          <NumBox label={t('Audiobook counts as started after, in minutes')} unit="min" value={c.MinAudiobookResume} min={0} max={600} onChange={(v) => set({ MinAudiobookResume: v })} />
        </SettingRow>
        <SettingRow name={t('Counts as finished')} help={t('Minutes before the end that count as finishing it')}>
          <NumBox label={t('Audiobook counts as finished, minutes before the end')} unit="min" value={c.MaxAudiobookResume} min={0} max={600} onChange={(v) => set({ MaxAudiobookResume: v })} />
        </SettingRow>
      </Group>
    </>
  );
}

/* ── Streaming ── */

function Streaming({ c, setC, o, setO }: { c: ServerConfig; setC: (p: Partial<ServerConfig>) => void; o: EncodingOptions; setO: (p: Partial<EncodingOptions>) => void }) {
  const mbps = Math.round((c.RemoteClientBitrateLimit || 0) / 100_000) / 10;
  const downmix = DOWNMIX.map((d) => (WORD_DOWNMIX.has(d.value) ? { ...d, label: t(d.label) } : d));
  return (
    <>
      <Group title={t('Remote streaming')}>
        <SettingRow name={t('Bitrate limit outside the home network')} help={t('0 means no limit. People can also have their own limit in Users')}>
          <NumBox label={t('Bitrate limit, in Mbps')} unit="Mbps" step={0.1} value={mbps} min={0} max={1000} onChange={(v) => setC({ RemoteClientBitrateLimit: Math.round(v * 1_000_000) })} />
        </SettingRow>
      </Group>
      <Group title={t('Audio')}>
        <SettingRow name={t('Downmix boost')} help={t('Makes stereo made from surround sound louder')}>
          <NumBox label={t('Downmix boost')} unit="×" step={0.5} value={o.DownMixAudioBoost} min={0.5} max={3} onChange={(v) => setO({ DownMixAudioBoost: v })} />
        </SettingRow>
        <SettingRow name={t('Stereo downmix')} help={t('How surround channels are mixed down to two')}>
          <Select label={t('Stereo downmix')} value={o.DownMixStereoAlgorithm} options={DOWNMIX.some((d) => d.value === o.DownMixStereoAlgorithm) ? downmix : [...downmix, { value: o.DownMixStereoAlgorithm, label: o.DownMixStereoAlgorithm }]}
            onChange={(v) => setO({ DownMixStereoAlgorithm: v })} />
        </SettingRow>
        <Sw name={t('Variable bitrate audio')} help={t('Better sound for the same size; a few old devices can’t play it')} on={o.EnableAudioVbr} set={(v) => setO({ EnableAudioVbr: v })} />
      </Group>
      <Group title={t('Video')}>
        <SettingRow name={t('Deinterlacing')} help={t('For TV recordings and old DVDs')}>
          <Select label={t('Deinterlacing')} value={o.DeinterlaceMethod} options={DEINTERLACE} onChange={(v) => setO({ DeinterlaceMethod: v })} />
        </SettingRow>
        <Sw name={t('Double the frame rate')} help={t('Smoother motion when deinterlacing, at twice the work')} on={o.DeinterlaceDoubleRate} set={(v) => setO({ DeinterlaceDoubleRate: v })} />
        <SettingRow name={t('Muxing queue')} help={t('Raise it only if conversions fail with “Too many packets buffered”')}>
          <NumBox label={t('Muxing queue size, in packets')} unit={t('packets')} width={60} value={o.MaxMuxingQueueSize} min={128} max={65536} onChange={(v) => setO({ MaxMuxingQueueSize: v })} />
        </SettingRow>
      </Group>
      <Group title={t('Subtitles')}>
        <Sw name={t('Fallback fonts')} help={t('Fonts used for burned-in subtitles when a video doesn’t bring its own')} on={o.EnableFallbackFont} set={(v) => setO({ EnableFallbackFont: v })} />
        {o.EnableFallbackFont && (
          <SettingRow name={t('Fallback fonts folder')} help={t('A folder of .ttf, .otf or .woff fonts on the server')}>
            <PathField value={o.FallbackFontPath ?? ''} onChange={(v) => setO({ FallbackFontPath: v })} label={t('Fallback fonts folder')} />
          </SettingRow>
        )}
      </Group>
      <Group title={t('Files')}>
        <SettingRow name={t('Transcode folder')} help={t('Where streams are written while they play')}>
          <PathField value={o.TranscodingTempPath ?? ''} onChange={(v) => setO({ TranscodingTempPath: v })} label={t('Transcode folder')} placeholder={t('Server cache folder')} />
        </SettingRow>
      </Group>
    </>
  );
}

/* ── Seek previews ── */

function Previews({ t: tp, set }: { t: TrickplayOptions; set: (p: Partial<TrickplayOptions>) => void }) {
  return (
    <>
      <Group title={t('Making previews')}>
        <Sw name={t('Decode on the GPU')} help={t('Uses the hardware acceleration set up under Transcoding')} on={tp.EnableHwAcceleration} set={(v) => set({ EnableHwAcceleration: v })} />
        <Sw name={t('Encode on the GPU')} help={t('Faster still, but not every GPU can do it')} on={tp.EnableHwEncoding} set={(v) => set({ EnableHwEncoding: v })} />
        <Sw name={t('Key frames only')} help={t('Much faster, but previews may be a few seconds off')} on={tp.EnableKeyFrameOnlyExtraction} set={(v) => set({ EnableKeyFrameOnlyExtraction: v })} />
        <SettingRow name={t('During library scans')} help={t('Scans can wait for new previews or carry on without them')}>
          <Select label={t('During library scans')} value={tp.ScanBehavior} minWidth={260}
            options={[{ value: 'NonBlocking', label: t('Make them in the background') }, { value: 'Blocking', label: t('Wait for them') }]}
            onChange={(v) => set({ ScanBehavior: v })} />
        </SettingRow>
        <SettingRow name={t('Priority')} help={t('Lower keeps playback smooth while previews are made')}>
          <Select label={t('Priority')} value={tp.ProcessPriority} options={PRIORITY.some((p) => p.value === tp.ProcessPriority) ? tOpts(PRIORITY) : [...tOpts(PRIORITY), { value: tp.ProcessPriority, label: tp.ProcessPriority }]}
            onChange={(v) => set({ ProcessPriority: v })} />
        </SettingRow>
        <SettingRow name={t('Threads')} help={t('0 lets FFmpeg decide')}>
          <NumBox label={t('Threads')} value={tp.ProcessThreads} min={0} max={64} onChange={(v) => set({ ProcessThreads: v })} />
        </SettingRow>
      </Group>
      <Group title={t('Images')}>
        <SettingRow name={t('One preview every')} help={t('Shorter gaps look smoother and take more space')}>
          <NumBox label={t('One preview every, in seconds')} unit="s" value={Math.round(tp.Interval / 100) / 10} step={0.5} min={1} max={600} onChange={(v) => set({ Interval: Math.round(v * 1000) })} />
        </SettingRow>
        <SettingRow name={t('Widths')} help={t('One set of previews per width, in pixels')}>
          <ChipList
            label={t('Widths')}
            items={tp.WidthResolutions.map(String)}
            placeholder="320"
            help={t('A width in pixels, between 64 and 3840.')}
            validate={(v) => (/^\d+$/.test(v) && +v >= 64 && +v <= 3840 ? null : t('Write a width in pixels, between 64 and 3840.'))}
            onChange={(list) => set({ WidthResolutions: list.map(Number).sort((a, b) => a - b) })}
          />
        </SettingRow>
        <SettingRow name={t('Previews per tile')} help={t('Previews are stored in grids of this size')}>
          <div class="srv-chiprow">
            <NumBox label={t('Previews across')} unit={t('across')} width={36} value={tp.TileWidth} min={1} max={100} onChange={(v) => set({ TileWidth: v })} />
            <NumBox label={t('Previews down')} unit={t('down')} width={36} value={tp.TileHeight} min={1} max={100} onChange={(v) => set({ TileHeight: v })} />
          </div>
        </SettingRow>
        <SettingRow name={t('JPEG quality')} help={t('Higher looks better and makes bigger files')}>
          <NumBox label={t('JPEG quality, in percent')} unit="%" value={tp.JpegQuality} min={1} max={100} onChange={(v) => set({ JpegQuality: v })} />
        </SettingRow>
        <SettingRow name={t('Compression')} help={t('From 2 to 31. Lower is better quality and bigger files')}>
          <NumBox label={t('Compression')} value={tp.Qscale} min={2} max={31} onChange={(v) => set({ Qscale: v })} />
        </SettingRow>
      </Group>
    </>
  );
}

