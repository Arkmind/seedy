import { daysFromToday, fmtDate, fmtMonthYear, fmtWeekdayDate } from '../../lib/format';
import { t } from '../../lib/i18n';

/* How the request pages word dates and people, for Seerr and for the music and book apps alike. */

/* "Fri 31 Oct" within the next month, else "Nov 2026". */
export const aheadDate = (d: Date) => (daysFromToday(d) <= 31 ? fmtWeekdayDate(d) : fmtMonthYear(d));

const ACTS = {
  asked: ['asked today', 'asked yesterday', 'asked {date}'],
  approved: ['approved today', 'approved yesterday', 'approved {date}'],
  declined: ['declined today', 'declined yesterday', 'declined {date}'],
  askedBy: ['asked by {name} today', 'asked by {name} yesterday', 'asked by {name} {date}']
} as const;

/* "asked Sat 17 Oct", "approved today", "asked by Mia yesterday". */
export function actLine(act: keyof typeof ACTS, iso: string | undefined, name = ''): string {
  const d = iso ? new Date(iso) : undefined;
  if (!d || Number.isNaN(d.getTime())) return '';
  const ago = -daysFromToday(d);
  const date = d.getFullYear() === new Date().getFullYear() ? fmtWeekdayDate(d) : fmtDate(d);
  return t(ACTS[act][ago <= 0 ? 0 : ago === 1 ? 1 : 2], { date, name });
}

/* "you", "Mia", or "someone" when the server doesn't say. */
export const requesterName = (r: { requestedByMe?: boolean; requestedBy?: string | null }) => (r.requestedByMe ? t('you') : r.requestedBy || t('someone'));
