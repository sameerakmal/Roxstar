import { Types } from 'mongoose';
import type { Socket } from 'socket.io';

import { roomMemberRepository } from '../repositories/index.js';
import { handleSocketConnected, handleSocketDisconnected } from '../services/roomService.js';
import { assembleRoomStateById } from '../services/roomStateAssembler.js';
import { addSocket, removeSocket } from '../services/presenceRegistry.js';
import { logger } from '../utils/logger.js';
import { getIdentity } from './authMiddleware.js';
import { CLIENT_EVENTS, ROOM_EVENTS } from './events.js';

// WebSocket concerns only: validate the request shape, check membership, delegate to
// services. No business rules live here.

type Ack = (response: { ok: true } | { ok: false; code: string }) => void;

function callAck(ack: unknown, response: { ok: true } | { ok: false; code: string }): void {
  if (typeof ack === 'function') {
    (ack as Ack)(response);
  }
}

async function onJoinRoom(socket: Socket, payload: unknown, ack: unknown): Promise<void> {
  const identity = getIdentity(socket);
  const roomIdRaw = (payload as { roomId?: unknown } | undefined)?.roomId;

  if (typeof roomIdRaw !== 'string' || !Types.ObjectId.isValid(roomIdRaw)) {
    callAck(ack, { ok: false, code: 'INVALID_ROOM_ID' });
    return;
  }

  const roomId = new Types.ObjectId(roomIdRaw);

  // Membership lives in MongoDB and is created over REST. A socket never creates it,
  // so there is no second source of truth.
  const membership = await roomMemberRepository.findActiveMembership(roomId, identity.userId);
  if (membership === null) {
    callAck(ack, { ok: false, code: 'NOT_A_MEMBER' });
    return;
  }

  await socket.join(roomIdRaw);
  const change = addSocket(roomIdRaw, identity.userId.toString(), socket.id);

  // Only the user's first socket changes presence; a second tab must not re-announce.
  if (change === 'FIRST_CONNECTION') {
    await handleSocketConnected(roomId, identity.userId);
  }

  // Snapshot goes to this socket alone, and is authoritative over the event stream.
  const state = await assembleRoomStateById(roomId);
  if (state !== null) {
    socket.emit(ROOM_EVENTS.roomState, state);
  }

  callAck(ack, { ok: true });
}

async function detachFromRoom(socket: Socket, roomIdRaw: string): Promise<void> {
  const identity = getIdentity(socket);
  const removal = removeSocket(roomIdRaw, identity.userId.toString(), socket.id);

  if (removal !== 'LAST_DISCONNECTION') {
    return;
  }

  // Presence only. Membership stays JOINED and spin participation is untouched, which
  // is what allows this user to reconnect and carry on.
  await handleSocketDisconnected(new Types.ObjectId(roomIdRaw), identity.userId);
}

async function onLeaveRoom(socket: Socket, payload: unknown, ack: unknown): Promise<void> {
  const roomIdRaw = (payload as { roomId?: unknown } | undefined)?.roomId;

  if (typeof roomIdRaw !== 'string' || !Types.ObjectId.isValid(roomIdRaw)) {
    callAck(ack, { ok: false, code: 'INVALID_ROOM_ID' });
    return;
  }

  await socket.leave(roomIdRaw);
  await detachFromRoom(socket, roomIdRaw);
  callAck(ack, { ok: true });
}

export function registerSocketHandlers(socket: Socket): void {
  const identity = getIdentity(socket);
  logger.info({ socketId: socket.id, userId: identity.userId.toString() }, 'Socket connected');

  socket.on(CLIENT_EVENTS.joinRoom, (payload: unknown, ack: unknown) => {
    void onJoinRoom(socket, payload, ack).catch((error: unknown) => {
      logger.error({ err: error, socketId: socket.id }, 'join_room failed');
      callAck(ack, { ok: false, code: 'INTERNAL_ERROR' });
    });
  });

  socket.on(CLIENT_EVENTS.leaveRoom, (payload: unknown, ack: unknown) => {
    void onLeaveRoom(socket, payload, ack).catch((error: unknown) => {
      logger.error({ err: error, socketId: socket.id }, 'leave_room failed');
      callAck(ack, { ok: false, code: 'INTERNAL_ERROR' });
    });
  });

  socket.on('disconnecting', (reason: string) => {
    // socket.rooms still holds the joined rooms here; after 'disconnect' it is empty.
    const joinedRooms = [...socket.rooms].filter((room) => room !== socket.id);

    void (async (): Promise<void> => {
      for (const roomIdRaw of joinedRooms) {
        await detachFromRoom(socket, roomIdRaw);
      }
    })().catch((error: unknown) => {
      logger.error({ err: error, socketId: socket.id }, 'disconnect cleanup failed');
    });

    logger.info({ socketId: socket.id, reason }, 'Socket disconnected');
  });
}
