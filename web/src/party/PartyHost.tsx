import type { ComponentType } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { useLocation } from '../lib/router';
import { editable } from '../screens/me/effects';

/* The Konami code (↑ ↑ ↓ ↓ ← → ← → B A) anywhere but the player opens Medu Dance Revolution. Only this listener is
   in the main bundle; the party, its song and its step charts load when the code is entered. */

const CODE = ['arrowup', 'arrowup', 'arrowdown', 'arrowdown', 'arrowleft', 'arrowright', 'arrowleft', 'arrowright', 'b', 'a'];
const onPlayer = (path: string) => path.toLowerCase().startsWith('/play/');

type PartyProps = { onClose: () => void };

export function PartyHost() {
  const loc = useLocation();
  const [open, setOpen] = useState(false);
  const [Party, setParty] = useState<ComponentType<PartyProps> | null>(null);

  useEffect(() => {
    if (open || onPlayer(loc.path)) return;
    let typed: string[] = [];
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      // Letters typed into a field are words, not a code.
      if (editable(e.target as Element | null)) { typed = []; return; }
      typed = [...typed, e.key.toLowerCase()].slice(-CODE.length);
      if (typed.length === CODE.length && typed.every((k, i) => k === CODE[i])) {
        typed = [];
        setOpen(true);
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [open, loc.path]);

  // Playback started from elsewhere (a cast from another app) ends the party.
  useEffect(() => { if (onPlayer(loc.path)) setOpen(false); }, [loc.path]);

  useEffect(() => {
    if (!open || Party) return;
    import('./Party').then((m) => setParty(() => m.Party), () => setOpen(false));
  }, [open, Party]);

  return open && Party ? <Party onClose={() => setOpen(false)} /> : null;
}
