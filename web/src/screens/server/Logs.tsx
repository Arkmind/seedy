import { memo } from 'preact/compat';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks';
import { t, tn } from '../../lib/i18n';
import { setQuery, useLocation } from '../../lib/router';
import { Icon } from '../../ui/icons';
import { toast } from '../../ui/overlay';
import { AdminLayout } from '../admin/AdminLayout';
import { getLogFiles, getLogText, getServerConfig, type LogFile, reasonText } from './api';
import { copyText, downloadText, fmtSize } from './format';
import { ListError } from './advanced-parts';
import { type Entry, fileLabel, isDaily, isError, isWarn, type Parsed, parseLog, shortFileName, titleFromLog, wantsTitle } from './logs';
import './advanced.css';

type LevelFilter = 'all' | 'warn' | 'error';
const WINDOW = 1500;
const OTHERS_SHOWN = 12;

/* Titles read from FFmpeg logs ("Sintel transcode"), kept for the session. */
const TITLE_KEY = 'seedy.admin.logTitles';
const titles: Record<string, string> = (() => { try { return JSON.parse(sessionStorage.getItem(TITLE_KEY) ?? '{}') as Record<string, string>; } catch { return {}; } })();
const saveTitles = () => { try { sessionStorage.setItem(TITLE_KEY, JSON.stringify(titles)); } catch { /* storage unavailable */ } };

let retention: number | undefined;

export function ServerLogs() {
  const loc = useLocation();
  const [files, setFiles] = useState<LogFile[] | null>(null);
  const [filesError, setFilesError] = useState<Error | null>(null);
  const [days, setDays] = useState<number | undefined>(retention);
  const [, setTitlesTick] = useState(0);
  const [showAll, setShowAll] = useState(false);

  /* Daily logs first (newest first), then FFmpeg and other logs, as on the board. */
  const ordered = useMemo(() => {
    if (!files) return [];
    const daily = files.filter((f) => isDaily(f.Name)).sort((a, b) => b.Name.localeCompare(a.Name));
    const other = files.filter((f) => !isDaily(f.Name)).sort((a, b) => b.DateModified.localeCompare(a.DateModified));
    return [...daily, ...other];
  }, [files]);
  const newestDaily = ordered.find((f) => isDaily(f.Name));

  const wanted = loc.query.get('file');
  const current = ordered.find((f) => f.Name.toLowerCase() === wanted?.toLowerCase()) ?? newestDaily ?? ordered[0];

  const loadFiles = () => getLogFiles().then((l) => { setFiles(l); setFilesError(null); return l; }, (e: Error) => { setFilesError(e); return null; });

  useEffect(() => {
    void loadFiles();
    if (retention === undefined) getServerConfig().then((c) => { retention = c.LogFileRetentionDays; setDays(retention); }, () => { /* keep the generic copy */ });
  }, []);

  /* Name the FFmpeg logs after what they played: their first line is the media source. */
  useEffect(() => {
    if (!files) return;
    const todo = files.filter((f) => wantsTitle(f.Name) && !(f.Name in titles) && f.Size < 2_000_000).slice(0, 24);
    let cancelled = false;
    (async () => {
      for (const f of todo) {
        if (cancelled) return;
        try {
          const title = titleFromLog(await getLogText(f.Name));
          titles[f.Name] = title ?? '';
          saveTitles();
          setTitlesTick((n) => n + 1);
        } catch { titles[f.Name] = ''; }
      }
    })();
    return () => { cancelled = true; };
  }, [files]);

  const isLive = (f?: LogFile) => !!f && (f === newestDaily || Date.now() - new Date(f.DateModified).getTime() < 90_000);

  const others = ordered.filter((f) => !isDaily(f.Name));
  const shown = showAll ? ordered : ordered.filter((f) => isDaily(f.Name) || others.indexOf(f) < OTHERS_SHOWN || f === current);
  const hidden = ordered.length - shown.length;

  return (
    <AdminLayout
      page="logs"
      title={t('Logs')}
      lede={days
        ? tn(days, "The server's log files, newest first. Kept for {n} day and slow-response warnings are set in Settings.", "The server's log files, newest first. Kept for {n} days and slow-response warnings are set in Settings.")
        : t("The server's log files, newest first. How long they’re kept and slow-response warnings are set in Settings.")}
      wide
    >
      {!files && filesError ? (
        <ListError message={t('Couldn’t load the log files.')} error={filesError} onRetry={() => void loadFiles()} />
      ) : (
        <div class="adv-logs">
          <section aria-label={t('Log files')} class="adv-files">
            {!files ? (
              [0, 1, 2, 3].map((i) => (
                <div key={i} class="adv-file" aria-hidden="true">
                  <span class="skel skel-line" style={{ display: 'block', width: '90px', height: '14px' }} />
                  <span class="skel skel-line" style={{ display: 'block', width: '170px', marginTop: '4px' }} />
                </div>
              ))
            ) : !ordered.length ? (
              <div class="adv-empty"><span class="adv-empty-title">{t('No log files')}</span><span class="adv-empty-text">{t('The server hasn’t written any logs yet.')}</span></div>
            ) : (
              <>
                {shown.map((f) => (
                  <button
                    key={f.Name}
                    type="button"
                    class="adv-file"
                    aria-current={f === current ? 'true' : undefined}
                    onClick={() => setQuery({ file: f === newestDaily ? null : f.Name })}
                  >
                    <span class="adv-file-top">
                      <span class="adv-file-label">{fileLabel(f, titles[f.Name] || undefined)}</span>
                      {isLive(f) && <span class="adv-writing"><span class="dot" />{t('Writing')}</span>}
                    </span>
                    <span class="adv-file-name adv-mono" title={f.Name}>{shortFileName(f.Name)} · {fmtSize(f.Size)}</span>
                  </button>
                ))}
                {hidden > 0 && (
                  <div class="adv-files-more"><button type="button" class="link-btn" onClick={() => setShowAll(true)}>{t('Show {n} more', { n: hidden })}</button></div>
                )}
              </>
            )}
          </section>
          {current ? (
            <LogViewer key={current.Name} file={current} live={isLive(current)} onRefreshFiles={loadFiles} initialQuery={loc.query.get('q') ?? ''} />
          ) : (
            <section class="adv-viewer" aria-label={t('Log')}>
              <div class="adv-log-skel" aria-hidden={files ? undefined : 'true'}>
                {files ? <span class="adv-empty-text">{t('Choose a log file.')}</span> : [70, 55, 82, 64, 48].map((w, i) => <span key={i} class="skel" style={{ width: `${w}%` }} />)}
              </div>
            </section>
          )}
        </div>
      )}
    </AdminLayout>
  );
}

function LogViewer({ file, live, onRefreshFiles, initialQuery }: {
  file: LogFile; live: boolean; onRefreshFiles: () => Promise<LogFile[] | null>; initialQuery: string;
}) {
  const [parsed, setParsed] = useState<Parsed | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [term, setTerm] = useState(initialQuery);
  const [level, setLevel] = useState<LevelFilter>('all');
  const [following, setFollowing] = useState(true);
  const [start, setStart] = useState<number | null>(null);
  const [hit, setHit] = useState(-1);
  const box = useRef<HTMLDivElement>(null);
  const size = useRef(-1);
  const parsedRef = useRef<Parsed | null>(null);
  const programmatic = useRef(false);

  const fetchText = async () => {
    try {
      const text = await getLogText(file.Name);
      const next = parseLog(text, parsedRef.current ?? undefined);
      parsedRef.current = next;
      setParsed(next);
      setError(null);
    } catch (e) {
      setError(e as Error);
    }
  };

  useEffect(() => { size.current = file.Size; void fetchText(); }, []);

  /* Tail: while following a file that's being written, re-read it whenever it grows. */
  useEffect(() => {
    if (!following || !live) return;
    let stop = false;
    let timer: number | undefined;
    const tick = async () => {
      const list = await onRefreshFiles();
      if (stop) return;
      const f = list?.find((x) => x.Name === file.Name);
      if (f && f.Size !== size.current) { size.current = f.Size; await fetchText(); }
      if (!stop) timer = window.setTimeout(() => void tick(), 2500);
    };
    timer = window.setTimeout(() => void tick(), 2500);
    return () => { stop = true; window.clearTimeout(timer); };
  }, [following, live, file.Name]);

  const filtered = useMemo(() => {
    const all = parsed?.entries ?? [];
    if (level === 'all') return all;
    return all.filter((e) => (level === 'warn' ? isWarn(e.level) : isError(e.level)));
  }, [parsed, level]);

  const q = term.trim().toLowerCase();
  const matches = useMemo(() => (q ? filtered.filter((e) => e.text.toLowerCase().includes(q) || e.time.includes(q)).map((e) => e.i) : []), [filtered, q]);
  const matchSet = useMemo(() => new Set(matches), [matches]);
  useEffect(() => setHit(-1), [q, level]);

  const from = start ?? Math.max(0, filtered.length - WINDOW);
  const visible = filtered.slice(from);

  /* Keep the newest lines in view while following. */
  useLayoutEffect(() => {
    const el = box.current;
    if (!el || !following || hit >= 0) return;
    programmatic.current = true;
    el.scrollTop = el.scrollHeight;
    requestAnimationFrame(() => { programmatic.current = false; });
  }, [parsed, following, level, visible.length]);

  const onScroll = () => {
    const el = box.current;
    if (!el || programmatic.current) return;
    const atEnd = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
    if (following && !atEnd) setFollowing(false);
  };

  const goTo = (dir: 1 | -1) => {
    if (!matches.length) return;
    const n = hit < 0 ? (dir > 0 ? 0 : matches.length - 1) : (hit + dir + matches.length) % matches.length;
    setHit(n);
    setFollowing(false);
    const idx = filtered.findIndex((e) => e.i === matches[n]);
    if (idx >= 0 && idx < from) setStart(Math.max(0, idx - 50));
    // Scroll the log itself, not the page.
    requestAnimationFrame(() => {
      const b = box.current;
      const el = b?.querySelector<HTMLElement>(`[data-i="${matches[n]}"]`);
      if (!b || !el) return;
      programmatic.current = true;
      b.scrollTop = el.offsetTop - b.clientHeight / 2 + el.offsetHeight / 2;
      requestAnimationFrame(() => { programmatic.current = false; });
    });
  };

  const copy = async () => {
    const text = filtered.map((e) => e.raw).join('\n');
    const ok = await copyText(text);
    toast(ok ? tn(filtered.length, 'Copied {n} line', 'Copied {n} lines') : t('Couldn’t copy. Use Download instead.'), ok ? 'ok' : 'error');
  };

  const structured = parsed?.structured ?? true;

  return (
    <section aria-label={file.Name} class="adv-viewer">
      <div class="adv-viewer-tools">
        <label class="adv-logsearch">
          <Icon name="search" size={16} />
          <input
            type="search"
            aria-label={t('Search this log')}
            placeholder={t('Search this log')}
            value={term}
            onInput={(e) => setTerm((e.currentTarget as HTMLInputElement).value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); goTo(e.shiftKey ? -1 : 1); } }}
          />
          {q && <span class="adv-logsearch-count" aria-live="polite">{hit >= 0 ? t('{i} of {n}', { i: hit + 1, n: matches.length }) : tn(matches.length, '{n} match', '{n} matches', { n: matches.length })}</span>}
        </label>
        <div role="group" aria-label={t('Level')} class="adv-levels">
          {([['all', 'All'], ['warn', 'Warnings'], ['error', 'Errors']] as const).map(([v, label]) => (
            <button key={v} type="button" aria-pressed={level === v ? 'true' : 'false'} disabled={!structured && v !== 'all'} onClick={() => { setLevel(v); setStart(null); }}>{t(label)}</button>
          ))}
        </div>
        <div class="adv-viewer-buttons">
          <button
            type="button"
            class="btn btn-secondary is-sm is-follow"
            aria-pressed={following && live ? 'true' : 'false'}
            disabled={!live}
            title={live ? undefined : t('This file is no longer being written')}
            onClick={() => { setFollowing((f) => !f); setHit(-1); setStart(null); }}
          >
            <span class={'dot' + (following && live ? '' : ' is-off')} />
            {following && live ? t('Following') : t('Follow')}
          </button>
          <button type="button" class="btn btn-secondary is-sm" disabled={!parsed} onClick={() => void copy()}>{t('Copy')}</button>
          <button type="button" class="btn btn-secondary is-sm" disabled={!parsed} onClick={() => parsed && downloadText(file.Name, parsed.text)}>{t('Download')}</button>
        </div>
      </div>

      {!parsed && error ? (
        <div class="adv-error" role="alert">
          <span class="dot is-danger" />
          <span style={{ flex: '1 1 260px' }}>{t('Couldn’t read {file}. {error}', { file: file.Name, error: reasonText(error) })}</span>
          <button type="button" class="btn btn-secondary is-sm" onClick={() => void fetchText()}>{t('Try again')}</button>
        </div>
      ) : !parsed ? (
        <div class="adv-log-skel" aria-busy="true">
          {[72, 58, 86, 64, 50, 78, 61, 44].map((w, i) => <span key={i} class="skel" style={{ width: `${w}%` }} />)}
        </div>
      ) : (
        <div ref={box} role="log" aria-label={t('Log lines')} aria-live="off" class="adv-log adv-mono" tabIndex={0} onScroll={onScroll}>
          {from > 0 && (
            <div class="adv-log-note">
              {tn(from, '{n} earlier line not shown.', '{n} earlier lines not shown.')}
              <button type="button" class="link-btn" onClick={() => { setFollowing(false); setStart(Math.max(0, from - 2000)); }}>{t('Show more')}</button>
            </div>
          )}
          {!filtered.length ? (
            <div class="adv-log-note">{level === 'all' ? t('This log is empty.') : level === 'warn' ? t('No warnings or errors in this log.') : t('No errors in this log.')}</div>
          ) : (
            visible.map((e) => <Line key={e.i} e={e} hit={matchSet.has(e.i)} current={hit >= 0 && matches[hit] === e.i} />)
          )}
        </div>
      )}
    </section>
  );
}

/* Lines only re-render when their entry or highlight changes, so tailing a long log stays cheap. */
const Line = memo(function Line({ e, hit, current }: { e: Entry; hit: boolean; current: boolean }) {
  const cls = 'adv-line' + (e.level === 'WRN' ? ' is-wrn' : isError(e.level) ? ' is-err' : '') + (hit ? ' is-hit' : '') + (current ? ' is-current' : '');
  if (!e.level) return <div class={cls} data-i={e.i}><span class="adv-ltext">{e.text || ' '}</span></div>;
  return (
    <div class={cls} data-i={e.i}>
      <span class="adv-ltime">{e.time}</span>
      <span class="adv-llevel">{e.level === 'FTL' ? 'FTL' : e.level}</span>
      <span class="adv-ltext">{e.text}</span>
    </div>
  );
});
