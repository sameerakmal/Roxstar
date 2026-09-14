import type { Server as HttpServer } from 'node:http';

import { Server as SocketIOServer } from 'socket.io';

import { setEventPublisher, type EventPublisher } from '../services/eventPublisher.js';
import { logger } from '../utils/logger.js';
import { identifySocket } from './authMiddleware.js';
import { registerSocketHandlers } from './handlers.js';

export function initializeSocketServer(httpServer: HttpServer): SocketIOServer {
  const io = new SocketIOServer(httpServer);

  io.use((socket, next) => {
    void identifySocket(socket, next);
  });

  io.on('connection', registerSocketHandlers);

  // Give the service layer a way to broadcast without importing Socket.IO itself.
  const publisher: EventPublisher = {
    toRoom(roomId, event, payload) {
      io.to(roomId).emit(event, payload);
    },
  };
  setEventPublisher(publisher);

  logger.info('Socket.IO server initialized');

  return io;
}
