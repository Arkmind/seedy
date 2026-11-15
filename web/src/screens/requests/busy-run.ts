import { useState } from 'preact/hooks';
import { toast } from '../../ui/overlay';

/* Runs a request action with its row marked busy: [after] gets the request as it is now (null once it's gone), then the
   toast, then [refresh] (the tabs' counts). A failure toasts with the server's reason. */
export function useBusyRun<R, N>(keyOf: (r: R) => string | number, after: (r: R, next: N) => void, refresh: () => Promise<void>) {
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const run = async (r: R, call: () => Promise<N>, done: string, failed: string) => {
    const k = String(keyOf(r));
    setBusy((b) => ({ ...b, [k]: true }));
    try {
      after(r, await call());
      toast(done);
      void refresh();
    } catch (e) {
      toast(failed + ((e as Error)?.message ? ': ' + (e as Error).message : ''), 'error');
    } finally {
      setBusy((b) => { const n = { ...b }; delete n[k]; return n; });
    }
  };
  return { run, busy: (r: R) => !!busy[String(keyOf(r))] };
}
