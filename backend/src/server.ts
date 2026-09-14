import { createServer } from 'node:http';

import { createApp } from './app.js';
import { loadConfig } from './config/index.js';
import { connectDatabase, disconnectDatabase } from './config/database.js';
import { logger } from './utils/logger.js';
import { initializeSocketServer } from './websocket/index.js';

async function start(): Promise<void> {
  const config = loadConfig();

  // Fail fast: a backend that cannot reach its database must not accept traffic.
  await connectDatabase(config.mongodbUri);

  const app = createApp();
  const httpServer = createServer(app);
  const io = initializeSocketServer(httpServer);

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
