import { defineConfig } from 'vitest/config';

// Runs inside `firebase emulators:exec` (npm run emulated): the files share one emulator, so they
// run one after another.
export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    fileParallelism: false,
    testTimeout: 30_000,
    hookTimeout: 30_000,
  },
});
