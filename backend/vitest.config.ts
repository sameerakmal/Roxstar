import { defineConfig } from 'vitest/config';

// Unit suite: no external dependencies, so `npm test` stays runnable anywhere.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['tests/unit/**/*.test.ts'],
    env: {
      LOG_LEVEL: 'silent',
    },
  },
});
