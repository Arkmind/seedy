import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { href, navigate } from '../../lib/router';
import { useStore } from '../../lib/store';
import { formatNumber, t } from '../../lib/i18n';
import { Icon } from '../../ui/icons';
import { toast } from '../../ui/overlay';
import { AdminLayout } from '../admin/AdminLayout';
import { getTasks, reasonText, startTask, stopTask, type TaskInfo } from './api';
import { groupOf, GROUPS, lastRunText, logNameFor, nextRun, nextText, scheduleText, sortTasks, taskDesc, taskName } from './tasks';
import { loadServerOffset, serverOffsetStore, serverTimeNote } from './status';
import { ListError } from './advanced-parts';
import { ScheduleSheet } from './ScheduleSheet';
import './advanced.css';

/* When a task was first seen running in this tab ("Running since 20:41"). Jellyfin doesn't report it. */
const START_KEY = 'seedy.admin.taskStarts';
function readStarts(): Record<string, number> {
  try { return JSON.parse(sessionStorage.getItem(START_KEY) ?? '{}') as Record<string, number>; } catch { return {}; }
}
function writeStarts(v: Record<string, number>) {
  try { sessionStorage.setItem(START_KEY, JSON.stringify(v)); } catch { /* storage unavailable */ }
}

export function ServerTasks() {
  const offset = useStore(serverOffsetStore);
  useEffect(loadServerOffset, []);
  const [tasks, setTasks] = useState<TaskInfo[] | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [starts, setStarts] = useState<Record<string, number>>(readStarts);
  const [editing, setEditing] = useState<TaskInfo | null>(null);
  const [pending, setPending] = useState<Record<string, boolean>>({});
  const prev = useRef<Map<string, TaskInfo['State']> | null>(null);
  const timer = useRef<number>();
  const alive = useRef(true);

  const poll = async () => {
    window.clearTimeout(timer.current);
    try {
      const list = await getTasks();
      if (!alive.current) return;
      setError(null);
      // Record when tasks start; forget the ones that finished.
      const before = prev.current;
      setStarts((s) => {
        const next = { ...s };
        for (const x of list) {
          if (x.State !== 'Idle') {
            if (!next[x.Id] && before && before.get(x.Id) === 'Idle') next[x.Id] = Date.now();
          } else delete next[x.Id];
        }
        writeStarts(next);
        return next;
      });
      prev.current = new Map(list.map((x) => [x.Id, x.State]));
      setTasks(list);
      timer.current = window.setTimeout(() => void poll(), list.some((x) => x.State !== 'Idle') ? 1000 : 10_000);
    } catch (e) {
      if (!alive.current) return;
      setError(e as Error);
      timer.current = window.setTimeout(() => void poll(), 15_000);
    }
  };

  useEffect(() => {
    alive.current = true;
    void poll();
    const onVisible = () => { if (document.visibilityState === 'visible') void poll(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      alive.current = false;
      window.clearTimeout(timer.current);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, []);

  const groups = useMemo(() => {
    if (!tasks) return [];
    return GROUPS.map((g) => ({ label: g, tasks: tasks.filter((x) => groupOf(x) === g).sort(sortTasks) })).filter((g) => g.tasks.length);
  }, [tasks]);

  const run = async (task: TaskInfo) => {
    setPending((p) => ({ ...p, [task.Id]: true }));
    try {
      await startTask(task.Id);
      setStarts((s) => { const n = { ...s, [task.Id]: Date.now() }; writeStarts(n); return n; });
      setTasks((list) => list?.map((x) => (x.Id === task.Id ? { ...x, State: 'Running', CurrentProgressPercentage: x.CurrentProgressPercentage ?? null } : x)) ?? list);
      void poll();
    } catch (e) {
      toast(t('Couldn’t start {task}. {error}', { task: taskName(task), error: reasonText(e) }), 'error', 6000);
    } finally {
      setPending((p) => { const n = { ...p }; delete n[task.Id]; return n; });
    }
  };

  const stop = async (task: TaskInfo) => {
    setPending((p) => ({ ...p, [task.Id]: true }));
    try {
      await stopTask(task.Id);
      setTasks((list) => list?.map((x) => (x.Id === task.Id ? { ...x, State: 'Cancelling' } : x)) ?? list);
      void poll();
    } catch (e) {
      toast(t('Couldn’t stop {task}. {error}', { task: taskName(task), error: reasonText(e) }), 'error', 6000);
    } finally {
      setPending((p) => { const n = { ...p }; delete n[task.Id]; return n; });
    }
  };

  let lede = t('Reading the server’s tasks…');
  if (tasks) {
    const running = tasks.filter((x) => x.State !== 'Idle').length;
    const now = new Date();
    const next = tasks
      .filter((x) => x.State === 'Idle')
      .map((x) => ({ task: x, at: nextRun(x, now, offset) }))
      .filter((x): x is { task: TaskInfo; at: Date } => !!x.at)
      .sort((a, b) => a.at.getTime() - b.at.getTime())[0];
    lede = [
      running ? t('{n} running', { n: running }) : t('Nothing running'),
      next ? t('next: {task} {when}', { task: taskName(next.task), when: nextText(next.at) }) : null
    ].filter(Boolean).join(' · ');
  } else if (error) lede = t('Couldn’t read the server’s tasks.');

  return (
    <AdminLayout page="tasks" title={t('Scheduled tasks')} lede={<span class="adv-tight">{lede}</span>} wide>
      {!tasks && error ? (
        <ListError message={t('Couldn’t load the scheduled tasks.')} error={error} onRetry={() => void poll()} />
      ) : !tasks ? (
        <TasksSkeleton />
      ) : (
        groups.map((g) => (
          <section key={g.label} class="adv-group" aria-label={t(g.label)}>
            <h2 class="adv-group-label">{t(g.label)}</h2>
            <div class="adv-list">
              {g.tasks.map((task) => (
                <TaskRow key={task.Id} task={task} startedAt={starts[task.Id]} busy={!!pending[task.Id]} onRun={run} onStop={stop} onSchedule={setEditing} />
              ))}
            </div>
          </section>
        ))
      )}
      <ScheduleSheet task={editing} onClose={() => setEditing(null)} onSaved={(saved) => { setTasks((list) => list?.map((x) => (x.Id === saved.Id ? saved : x)) ?? list); void poll(); }} />
    </AdminLayout>
  );
}

function TaskRow({ task, startedAt, busy, onRun, onStop, onSchedule }: {
  task: TaskInfo; startedAt?: number; busy: boolean; onRun: (task: TaskInfo) => void; onStop: (task: TaskInfo) => void; onSchedule: (task: TaskInfo) => void;
}) {
  const offset = useStore(serverOffsetStore);
  const name = taskName(task);
  const running = task.State !== 'Idle';
  const pct = task.CurrentProgressPercentage;
  const hasPct = typeof pct === 'number' && pct > 0;
  const last = lastRunText(task, startedAt);
  const logLink = last.logDay ? '/admin/logs?file=' + encodeURIComponent(logNameFor(last.logDay)) + '&q=' + encodeURIComponent(task.Name) : '/admin/logs';
  const schedule = scheduleText(task.Triggers);
  /* Daily and weekly times are the server's clock: say so when it isn't this browser's. */
  const onServerClock = task.Triggers.some((x) => x.Type === 'DailyTrigger' || x.Type === 'WeeklyTrigger') && !!serverTimeNote(offset);

  return (
    <div class="adv-trow">
      <div class="adv-tmain">
        <button type="button" class="adv-tname" aria-label={t('{task}: change when it runs', { task: name })} onClick={() => onSchedule(task)}>{name}</button>
        <span class="adv-tdesc">{taskDesc(task)}</span>
        {running && (
          <div class="adv-tprogress">
            <div
              class={'adv-tbar' + (hasPct ? '' : ' is-indeterminate')}
              role="progressbar"
              aria-label={t('{task} progress', { task: name })}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={hasPct ? Math.round(pct!) : undefined}
            >
              <i style={{ width: `${hasPct ? Math.min(100, pct!) : 0}%` }} />
            </div>
            <span class="adv-tpct">{hasPct ? formatNumber(Math.round(pct!) / 100, { style: 'percent' }) : ''}</span>
          </div>
        )}
      </div>
      <div class="adv-twhen">
        <span class="adv-tsched">{onServerClock ? t('{schedule} (server time)', { schedule }) : schedule}</span>
        <span class={'adv-tlast' + (last.failed ? ' is-failed' : '')} title={last.title}>
          {last.text}
          {last.failed && (
            <>
              {' · '}
              <a href={href(logLink)} onClick={(e) => { if (e.button === 0 && !e.metaKey && !e.ctrlKey) { e.preventDefault(); navigate(logLink); } }}>{t('see logs')}</a>
            </>
          )}
        </span>
      </div>
      <div class="adv-tbuttons">
        {running ? (
          <button type="button" class="btn btn-secondary is-sm" aria-label={t('Stop {task}', { task: name })} disabled={busy || task.State === 'Cancelling'} onClick={() => onStop(task)}>
            {task.State === 'Cancelling' ? t('Stopping…') : t('Stop')}
          </button>
        ) : (
          <button type="button" class="btn btn-secondary is-sm" aria-label={t('Run {task} now', { task: name })} disabled={busy} onClick={() => onRun(task)}>
            <Icon name="play" size={14} />
            {t('Run')}
          </button>
        )}
        <button type="button" class="adv-outline" aria-label={t('Change when {task} runs', { task: name })} onClick={() => onSchedule(task)}>{t('Schedule')}</button>
      </div>
    </div>
  );
}

function TasksSkeleton() {
  return (
    <>
      {[4, 4, 2].map((n, g) => (
        <section key={g} class="adv-group" aria-hidden="true">
          <span class="adv-group-label"><span class="skel skel-line" style={{ display: 'block', width: '110px', height: '13px', margin: '2px 0' }} /></span>
          <div class="adv-list">
            {Array.from({ length: n }, (_, i) => (
              <div key={i} class="adv-skel-row">
                <span style={{ flex: '1 1 280px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <span class="skel skel-line" style={{ width: `${160 + ((i * 53) % 80)}px`, height: '15px' }} />
                  <span class="skel skel-line" style={{ width: `${200 + ((i * 37) % 60)}px` }} />
                </span>
                <span style={{ flex: '0 1 220px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <span class="skel skel-line" style={{ width: '110px' }} />
                  <span class="skel skel-line" style={{ width: '170px' }} />
                </span>
                <span class="skel" style={{ width: '170px', height: '40px', borderRadius: '12px' }} />
              </div>
            ))}
          </div>
        </section>
      ))}
    </>
  );
}
