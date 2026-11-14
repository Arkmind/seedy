/** Opens the header's search, like pressing "/" (the empty pages' "Find something to add"). SearchBox listens. */
export const OPEN_SEARCH = 'seedy:open-search';
export const openSearch = () => window.dispatchEvent(new Event(OPEN_SEARCH));
