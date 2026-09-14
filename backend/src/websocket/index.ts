import type { Server as HttpServer } from 'node:http';

import { Server as SocketIOServer } from 'socket.io';

import { logger } from '../utils/logger.js';
import { registerSocketHandlers } from './handlers.js';

export function initializeSocketServer(httpServer: HttpServer): SocketIOServer {
  const io = new SocketIOServer(httpServer);

  io.on('connection', registerSocketHandlers);

  logger.info('Socket.IO server initialized');

  return io;
}
