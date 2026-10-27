import { useEffect, useLayoutEffect } from 'preact/hooks';
import { useLocation } from '../lib/router';
import { useStore } from '../lib/store';
import { AudiobookPlayer } from './AudiobookPlayer';
import { audioStore, openPlayer, pause } from './engine';
import { MiniBar } from './MiniBar';
import { NowPlaying } from './NowPlaying';
import './audio.css';

/* Mounted once by the app: the player bar while something plays (pages leave room for it), and the full-screen player
   when it's open. Neither shows on the video player or the reader; starting a video pauses the music. */
export function AudioHost() {
  const loc = useLocation();
  const s = useStore(audioStore);
  const video = /^\/play\//i.test(loc.path);
  const hidden = video || /^\/read\//i.test(loc.path);
  const shown = !!s.current && !hidden;

  useEffect(() => { if (video) pause(); }, [video]);
  // Going somewhere else (a link in the player, Back) closes the full-screen player.
  useEffect(() => { if (audioStore.get().open) openPlayer(false); }, [loc.path]);
  useLayoutEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('ap-has-bar', shown);
    return () => root.classList.remove('ap-has-bar');
  }, [shown]);

  if (!shown) return null;
  return (
    <>
      <MiniBar />
      {s.open && (s.mode === 'audiobook' ? <AudiobookPlayer /> : <NowPlaying />)}
    </>
  );
}
