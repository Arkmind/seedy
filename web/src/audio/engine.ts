/* The audio engine behind the music and audiobook players (mini bar, Now playing, Audiobook player).
   Contract shared by the music and books screens; the implementation lives here. */

export type PlayOptions = {
  /* Where to start the first item, in ticks. */
  startTicks?: number;
  /* Open the full-screen player instead of only the mini bar. */
  open?: boolean;
  /* Shuffle the items. */
  shuffle?: boolean;
  /* What the queue plays from, shown as "Playing from album …" (album, playlist, artist, mix, library). */
  source?: { kind: 'album' | 'playlist' | 'artist' | 'mix' | 'library' | 'genre' | 'songs'; id?: string; name: string };
};

/* Plays these tracks (Audio item ids) from startIndex, replacing the queue. */
export async function playTracks(_ids: string[], _startIndex = 0, _opts: PlayOptions = {}): Promise<void> {
  throw new Error('audio engine not implemented yet');
}

/* Plays an album, playlist, artist or genre by id: their tracks in order (or shuffled). */
export async function playContainer(_id: string, _opts: PlayOptions = {}): Promise<void> {
  throw new Error('audio engine not implemented yet');
}

/* Plays an audiobook (AudioBook item id), resuming where this profile left off unless startTicks is given. */
export async function playAudiobook(_id: string, _opts: PlayOptions = {}): Promise<void> {
  throw new Error('audio engine not implemented yet');
}
