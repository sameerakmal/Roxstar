import type { Socket } from 'socket.io';
import { Types } from 'mongoose';

import { userRepository } from '../repositories/index.js';

// Socket identity, mirroring middleware/currentUser.ts for HTTP.
//
// SAME DEMO STAND-IN, SAME CAVEAT: the client states who it is and the server believes
// it. There is no credential and no session. It is isolated here so a real
// authentication step could replace it without touching any handler or service.
export type SocketIdentity = { userId: Types.ObjectId; displayName: string };

// Socket.IO types `socket.data` as `any`, so identities are held here instead. A
// WeakMap keeps the lookup fully typed and drops entries when a socket is collected.
const identities = new WeakMap<Socket, SocketIdentity>();

function readUserId(socket: Socket): string | undefined {
  const fromAuth = (socket.handshake.auth as { userId?: unknown } | undefined)?.userId;
  if (typeof fromAuth === 'string' && fromAuth.trim() !== '') {
    return fromAuth;
  }
  const fromHeader = socket.handshake.headers['x-user-id'];
  return typeof fromHeader === 'string' && fromHeader.trim() !== '' ? fromHeader : undefined;
}

export async function identifySocket(socket: Socket, next: (error?: Error) => void): Promise<void> {
  const raw = readUserId(socket);

  if (raw === undefined) {
    next(new Error('MISSING_USER_ID'));
    return;
  }
  if (!Types.ObjectId.isValid(raw)) {
    next(new Error('INVALID_USER_ID'));
    return;
  }

  const userId = new Types.ObjectId(raw);
  const user = await userRepository.findUserById(userId);
  if (user === null) {
    next(new Error('UNKNOWN_USER'));
    return;
  }

  identities.set(socket, { userId, displayName: user.displayName });
  next();
}

export function getIdentity(socket: Socket): SocketIdentity {
  const identity = identities.get(socket);
  if (identity === undefined) {
    throw new Error('Socket reached a handler without identification');
  }
  return identity;
}
