/* The colours behind a profile's first letter while it has no picture, taking turns down a list of people. */
export const AVATAR_COLOURS = ['#f0f0f0', '#9fd3ff', '#b8f59f', '#ffc98a', '#e3c4ff', '#ffb3e6', '#a8e6df', '#f0b9a0'];
export const avatarColour = (index: number) => AVATAR_COLOURS[Math.max(0, index) % AVATAR_COLOURS.length];
