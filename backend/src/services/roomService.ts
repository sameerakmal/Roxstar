import type { Types } from 'mongoose';

import {
  NotAMemberError,
  RoomClosedError,
  RoomNotFoundError,
} from '../errors/DomainError.js';
import { DuplicateKeyError } from '../errors/RepositoryError.js';
import { roomMemberRepository, roomRepository, userRepository } from '../repositories/index.js';
import type { RoomRecord } from '../repositories/roomRepository.js';
import { ROOM_EVENTS, type LeaveReason } from '../websocket/events.js';
import type { RoomStateDto } from './dto.js';
import { publishToRoom } from './eventPublisher.js';
import { assembleParticipantDtos, assembleRoomState } from './roomStateAssembler.js';
import { handleParticipantLeft } from './spinService.js';

export type JoinOutcome = {
  created: boolean;
  state: RoomStateDto;
};

export type ShareOutcome<T> = {
  created: boolean;
  value: T;
};

async function requireRoom(roomId: Types.ObjectId): Promise<RoomRecord> {
  const room = await roomRepository.findRoomById(roomId);
  if (room === null) {
    throw new RoomNotFoundError(roomId.toString());
  }
  return room;
}

// Reading room state is a room operation, so it requires active membership
// (TASKS RM-3: reject acting on a room you are not a member of).
export async function requireActiveMembership(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<void> {
  const membership = await roomMemberRepository.findActiveMembership(roomId, userId);
  if (membership === null) {
    throw new NotAMemberError(roomId.toString());
  }
}

// The creator becomes the owner and is joined automatically: a room whose owner is
// not a member could not be read back by its own creator.
export async function createRoom(ownerUserId: Types.ObjectId): Promise<RoomStateDto> {
  const room = await roomRepository.createRoom(ownerUserId);
  await roomMemberRepository.joinRoom(room._id, ownerUserId);
  return assembleRoomState(room);
}

export async function getRoomState(
  roomId: Types.ObjectId,
  callerUserId: Types.ObjectId,
): Promise<RoomStateDto> {
  const room = await requireRoom(roomId);
  await requireActiveMembership(roomId, callerUserId);
  return assembleRoomState(room);
}

async function userSummary(userId: Types.ObjectId): Promise<{ userId: string; displayName: string }> {
  const user = await userRepository.findUserById(userId);
  return { userId: userId.toString(), displayName: user?.displayName ?? 'Unknown user' };
}

// Persist-then-broadcast: callers invoke this only after their write has been awaited.
export async function broadcastUserJoined(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<void> {
  const [user, participants] = await Promise.all([
    userSummary(userId),
    assembleParticipantDtos(roomId),
  ]);
  publishToRoom(roomId.toString(), ROOM_EVENTS.userJoined, {
    roomId: roomId.toString(),
    user,
    participants,
  });
}

export async function broadcastUserLeft(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
  reason: LeaveReason,
): Promise<void> {
  const [user, participants] = await Promise.all([
    userSummary(userId),
    assembleParticipantDtos(roomId),
  ]);
  publishToRoom(roomId.toString(), ROOM_EVENTS.userLeft, {
    roomId: roomId.toString(),
    user,
    reason,
    participants,
  });
}

// A dropped socket changes presence only. Membership stays JOINED and spin
// participation is untouched, which is what allows the user to reconnect and resume.
export async function handleSocketDisconnected(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<void> {
  await roomMemberRepository.setConnectionState(roomId, userId, 'DISCONNECTED');
  await broadcastUserLeft(roomId, userId, 'DISCONNECTED');
}

export async function handleSocketConnected(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<void> {
  await roomMemberRepository.setConnectionState(roomId, userId, 'CONNECTED');
  await broadcastUserJoined(roomId, userId);
}

// Idempotent join. A repeat from the same user is the same outcome the caller asked
// for, so it succeeds with `created: false` rather than erroring.
//
// The pre-check is a fast path for the sequential case, NOT the safety mechanism: two
// simultaneous requests can both pass it. The unique partial index settles that race,
// and the resulting DuplicateKeyError is folded into the same idempotent success —
// so concurrency produces one membership and two successful responses.
export async function joinRoom(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<JoinOutcome> {
  const room = await requireRoom(roomId);
  if (room.status !== 'ACTIVE') {
    throw new RoomClosedError(roomId.toString());
  }

  const existing = await roomMemberRepository.findActiveMembership(roomId, userId);
  if (existing !== null) {
    return { created: false, state: await assembleRoomState(room) };
  }

  try {
    await roomMemberRepository.joinRoom(roomId, userId);
    const state = await assembleRoomState(room);
    await broadcastUserJoined(roomId, userId);
    return { created: true, state };
  } catch (error: unknown) {
    if (error instanceof DuplicateKeyError) {
      return { created: false, state: await assembleRoomState(room) };
    }
    throw error;
  }
}

// Leaving is idempotent at the database level, but a caller who was never a member is
// a genuine invalid operation (TASKS RM-3) and is rejected.
//
// Phase 3 limitation: the owner may leave and the room stays ACTIVE. Ownership does
// not transfer. No owner-only operation exists until Start Spin in Phase 4, where the
// admin-departure rule is decided and scored.
export async function leaveRoom(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<RoomStateDto> {
  const room = await requireRoom(roomId);

  const left = await roomMemberRepository.leaveRoom(roomId, userId);
  if (left === null) {
    throw new NotAMemberError(roomId.toString());
  }

  // An explicit leave removes the user from a running spin (unlike a disconnect).
  await handleParticipantLeft(roomId, userId);
  await broadcastUserLeft(roomId, userId, 'LEFT');

  return assembleRoomState(room);
}
