import type { Socket } from 'socket.io';

import { logger } from '../utils/logger.js';

// Phase 1 scope: connection lifecycle logging only.
// Room joining, presence, draft sharing and spin events arrive in later phases.
export function registerSocketHandlers(socket: Socket): void {
  logger.info({ socketId: socket.id }, 'Socket connected');

  socket.on('disconnect', (reason: string) => {
    logger.info({ socketId: socket.id, reason }, 'Socket disconnected');
  });
}
