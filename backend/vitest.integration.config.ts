import { defineConfig } from 'vitest/config';

// Integration suite: requires a running MongoDB (see infrastructure/docker-compose.yml).
// Runs single-threaded because the specs share one database and clear collections
// between tests.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['tests/integration/**/*.test.ts'],
    fileParallelism: false,
    testTimeout: 20000,
    hookTimeout: 30000,
    env: {
      LOG_LEVEL: 'silent',
    },
  },
});
