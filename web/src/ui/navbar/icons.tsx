/* Icons the Navigation bar board draws that the shared set doesn't have (same 24 px grid, round caps). */

const PATHS = {
  // Requests: a tray with a down arrow.
  request: ['M12 3v10', 'M8 9l4 4 4-4', 'M4 14v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4'],
  // The small ↗ after "Requests" and "Find it on Seerr".
  out: ['M14 4h6v6', 'M20 4l-9 9'],
  // Library kinds, as in the board's libraries menu.
  film: ['M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4'],
  tv: ['M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM8 21h8M9 2l3 4 3-4'],
  layers: ['M12 3l9 5-9 5-9-5zM3 13l9 5 9-5'],
  camera: ['M3 8h4l2-3h6l2 3h4v11H3zM12 9.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z'],
  antenna: ['M12 12v9M8.5 8.5a5 5 0 0 1 7 0M5.6 5.6a9 9 0 0 1 12.8 0'],
  playlist: ['M4 6h12M4 12h12M4 18h8M18 15v6l4-3z'],
  music: ['M9 18V5l11-2v13', 'M6.5 15.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z', 'M17.5 13.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z'],
  book: ['M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z', 'M5 17a3 3 0 0 1 3-3h11'],
  folder: ['M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'],
  // Bottom tabs (the board's renderVals paths).
  home: ['M4 11l8-7 8 7v9a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z'],
  grid: ['M4 4h6.5v6.5H4zM13.5 4H20v6.5h-6.5zM4 13.5h6.5V20H4zM13.5 13.5H20V20h-6.5z'],
  heart: ['M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z']
} as const;

export type NbIconName = keyof typeof PATHS;

export function NbIcon({ name, size = 18, stroke = 2.2, class: cls, style }: { name: NbIconName; size?: number; stroke?: number; class?: string; style?: string }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" class={'ic' + (cls ? ' ' + cls : '')} style={style}
      fill="none" stroke="currentColor" stroke-width={stroke}>
      {PATHS[name].map((d) => <path key={d} d={d} />)}
    </svg>
  );
}

/* The library kind's icon in the libraries menu. */
export function libraryIcon(collectionType?: string): NbIconName {
  switch (collectionType) {
    case 'movies': return 'film';
    case 'tvshows': return 'tv';
    case 'boxsets': return 'layers';
    case 'homevideos': case 'photos': return 'camera';
    case 'livetv': return 'antenna';
    case 'playlists': return 'playlist';
    case 'music': case 'musicvideos': return 'music';
    case 'books': return 'book';
    default: return 'folder';
  }
}
