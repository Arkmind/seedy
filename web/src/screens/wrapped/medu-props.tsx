import { Medu, type MeduMood } from '../../ui/medu';

/* Medu with her props at any size: under 80 px Medu draws a simpler self, so small ones are drawn at 96 and scaled. */
export function MeduProps({ mood, size, glow = false }: { mood: MeduMood; size: number; glow?: boolean }) {
  if (size >= 80) return <Medu mood={mood} size={size} glow={glow} />;
  const k = size / 96;
  return (
    <span style={`display: block; flex: none; width: ${size}px; height: ${Math.round(size * 1.32)}px`}>
      <span style={`display: block; width: 96px; transform: scale(${k.toFixed(4)}); transform-origin: 0 0`}><Medu mood={mood} size={96} glow={glow} /></span>
    </span>
  );
}
