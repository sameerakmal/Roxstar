import { createServer } from 'node:http';

import { createApp } from './app.js';
import { loadConfig } from './config/index.js';
import { connectDatabase, disconnectDatabase } from './config/database.js';
import { syncAllIndexes } from './models/index.js';
import { recoverSpins } from './services/spinRecoveryService.js';
import { setEliminationIntervalMs } from './services/spinScheduler.js';
import { stopAllSpinTimers } from './services/spinService.js';
import { logger } from './utils/logger.js';
import { initializeSocketServer } from './websocket/index.js';

async function start(): Promise<void> {
  const config = loadConfig();

  // Fail fast: a backend that cannot reach its database must not accept traffic.
  await connectDatabase(config.mongodbUri);

  // The unique and partial indexes carry real invariants (one active spin per room,
  // one active membership per user), so they are built before the server accepts traffic.
  await syncAllIndexes();

  setEliminationIntervalMs(config.spinEliminationIntervalMs);

  const app = createApp();
  const httpServer = createServer(app);
  // The publisher must exist before recovery runs, so catch-up eliminations broadcast.
  const io = initializeSocketServer(httpServer);

  // Reconcile state left behind by a previous process: stale connection flags, orphan
  // WAITING spins, and RUNNING spins owed eliminations. Runs before accepting traffic.
  await recoverSpins();

  // Listen errors (EADDRINUSE, EACCES) surface asynchronously, after start() has
  // already resolved, so they must be handled here rather than by start().catch().
  httpServer.on('error', (error: NodeJS.ErrnoException) => {
    logger.fatal({ err: error, port: config.port }, 'HTTP server failed to bind');
    process.exit(1);
  });

  httpServer.listen(config.port, () => {
    logger.info({ port: config.port, env: config.nodeEnv }, 'Backend listening');
  });

  const shutdown = (signal: string): void => {
    logger.info({ signal }, 'Shutting down');
    stopAllSpinTimers();
    void io.close(() => {
      httpServer.close(() => {
        void disconnectDatabase().finally(() => {
          process.exit(0);
        });
      });
    });
  };

  process.on('SIGTERM', () => {
    shutdown('SIGTERM');
  });
  process.on('SIGINT', () => {
    shutdown('SIGINT');
  });
}

start().catch((error: unknown) => {
  logger.fatal({ err: error }, 'Failed to start backend');
  process.exit(1);
});
