/**
 * How Settings' "sign out" leaves: set by `App.tsx` in the server's Firebase mode, where the
 * Google account is left too (or the page would sign straight back in); null in the password mode,
 * where the server's own logout is all there is. Kept apart from `google.ts` so Settings does not
 * pull Firebase into a page that has no use for it.
 */
export const accountSignOut: { leave: (() => Promise<void>) | null } = { leave: null };
