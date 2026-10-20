import { useEffect, useState } from 'preact/hooks';
import { t } from '../../lib/i18n';
import { Segmented, SelectField } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { Sheet, toast } from '../../ui/overlay';
import { type DayOfWeek, reasonText, setTaskTriggers, type TaskInfo, type TaskTrigger } from './api';
import { runLength, weekdayLabel, WEEKDAYS } from './format';
import { intervalText, minutesOf, taskName, ticksOfMinutes, triggerText } from './tasks';

type Kind = 'daily' | 'weekly' | 'interval' | 'startup';

const TIMES = Array.from({ length: 48 }, (_, i) => {
  const m = i * 30;
  return { value: m, label: `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}` };
});
const INTERVAL_MINUTES = [15, 30, 45, 60, 120, 180, 240, 360, 480, 720, 1440];
/* Labels are built at render, in the profile's language: "Every 15 minutes", "Every hour", "Every 24 hours"; "Sunday". */
const intervals = () => INTERVAL_MINUTES.map((m) => ({ value: m, label: intervalText(ticksOfMinutes(m)) }));
const days = () => WEEKDAYS.map((d) => ({ value: d as DayOfWeek, label: weekdayLabel(d) }));

const same = (a: TaskTrigger, b: TaskTrigger) =>
  a.Type === b.Type && (a.TimeOfDayTicks ?? 0) === (b.TimeOfDayTicks ?? 0) && (a.IntervalTicks ?? 0) === (b.IntervalTicks ?? 0) && (a.DayOfWeek ?? '') === (b.DayOfWeek ?? '');

/* Edits a task's triggers: daily at a time, weekly on a day, every N hours, or at startup. */
export function ScheduleSheet({ task, onClose, onSaved }: { task: TaskInfo | null; onClose: () => void; onSaved: (saved: TaskInfo) => void }) {
  const [draft, setDraft] = useState<TaskTrigger[]>([]);
  const [kind, setKind] = useState<Kind>('daily');
  const [time, setTime] = useState(180);
  const [day, setDay] = useState<DayOfWeek>('Sunday');
  const [every, setEvery] = useState(1440);
  const [saving, setSaving] = useState(false);
  const [note, setNote] = useState('');

  useEffect(() => {
    if (!task) return;
    setDraft(task.Triggers.map((x) => ({ ...x })));
    setKind('daily');
    setTime(180);
    setDay('Sunday');
    setEvery(1440);
    setNote('');
  }, [task?.Id]);

  if (!task) return <Sheet open={false} onClose={onClose} label={t('Schedule')}>{null}</Sheet>;
  const name = taskName(task);

  const candidate = (): TaskTrigger => {
    switch (kind) {
      case 'daily': return { Type: 'DailyTrigger', TimeOfDayTicks: ticksOfMinutes(time) };
      case 'weekly': return { Type: 'WeeklyTrigger', TimeOfDayTicks: ticksOfMinutes(time), DayOfWeek: day };
      case 'interval': return { Type: 'IntervalTrigger', IntervalTicks: ticksOfMinutes(every) };
      default: return { Type: 'StartupTrigger' };
    }
  };

  const add = () => {
    const next = candidate();
    if (draft.some((x) => same(x, next))) { setNote(t('It already runs {schedule}.', { schedule: triggerText(next).toLowerCase() })); return; }
    setNote('');
    setDraft((d) => [...d, next]);
  };

  const changed = draft.length !== task.Triggers.length || draft.some((x, i) => !same(x, task.Triggers[i]));

  const save = async () => {
    setSaving(true);
    try {
      await setTaskTriggers(task.Id, draft);
      onSaved({ ...task, Triggers: draft });
      toast(t('Schedule saved for {task}', { task: name }));
      onClose();
    } catch (e) {
      toast(t('Couldn’t save the schedule. {error}', { error: reasonText(e) }), 'error', 6000);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Sheet open={!!task} onClose={onClose} label={t('Change when {task} runs', { task: name })}>
      <div class="sheet-head">
        <h2 class="sheet-title">{name}</h2>
        <button type="button" class="btn icon-btn is-bare is-36" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <p class="sheet-text">{t('When it runs on its own. You can always start it from the list with Run.')}</p>

      <div class="adv-trigger-list" role="list" aria-label={t('Current schedule')}>
        {draft.length ? draft.map((tr, i) => (
          <div key={i} class="adv-trigger" role="listitem">
            <span class="adv-trigger-text">
              {triggerText(tr)}
              {!!tr.MaxRuntimeTicks && <small>{t('Stops if it runs longer than {time}', { time: runLength(minutesOf(tr.MaxRuntimeTicks) * 60_000) })}</small>}
            </span>
            <button type="button" class="btn icon-btn is-bare is-36" aria-label={t('Remove “{schedule}”', { schedule: triggerText(tr) })} onClick={() => setDraft((d) => d.filter((_, j) => j !== i))}>
              <Icon name="close" size={16} />
            </button>
          </div>
        )) : <div class="adv-trigger-none">{t('Not scheduled. It only runs when you start it.')}</div>}
      </div>

      <div class="adv-add-trigger">
        <span class="adv-add-trigger-title">{t('Add a time')}</span>
        <Segmented<Kind>
          label={t('How often')}
          value={kind}
          onChange={(k) => { setKind(k); setNote(''); }}
          options={[
            { value: 'daily', label: t('Daily') },
            { value: 'weekly', label: t('Weekly') },
            { value: 'interval', label: t('Every…') },
            { value: 'startup', label: t('On startup') }
          ]}
        />
        <div class="adv-add-trigger-row">
          {kind === 'weekly' && <SelectField label={t('Day')} value={day} options={days()} onChange={setDay} minWidth={150} />}
          {(kind === 'daily' || kind === 'weekly') && <SelectField label={t('Time')} value={time} options={TIMES} onChange={setTime} minWidth={120} />}
          {kind === 'interval' && <SelectField label={t('Interval')} value={every} options={intervals()} onChange={setEvery} minWidth={200} />}
          {kind === 'startup' && <span class="sheet-text">{t('Each time the server starts.')}</span>}
          <button type="button" class="btn btn-secondary" onClick={add}><Icon name="plus" size={14} stroke={2.6} />{t('Add')}</button>
        </div>
        {note && <p class="form-error" role="alert"><Icon name="alert" size={16} />{note}</p>}
      </div>

      <div class="sheet-actions">
        <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
        <button type="button" class="btn btn-primary is-sm" disabled={!changed || saving} onClick={() => void save()}>{saving ? t('Saving…') : t('Save schedule')}</button>
      </div>
    </Sheet>
  );
}
