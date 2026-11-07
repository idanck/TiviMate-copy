import { signal } from '@preact/signals';

/** Whether any key has been pressed since the app opened (ui/App.tsx): until then the app may move focus itself. */
export const remoteUsed = signal(false);
