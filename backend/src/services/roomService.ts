import type { Types } from 'mongoose';

import {
  NotAMemberError,
  RoomClosedError,
  RoomNotFoundError,
} from '../errors/DomainError.js';
import { DuplicateKeyError } from '../errors/RepositoryError.js';
import { roomMemberRepository, roomRepository } from '../repositories/index.js';
import type { RoomRecord } from '../repositories/roomRepository.js';
import type { RoomStateDto } from './dto.js';
import { assembleRoomState } from './roomStateAssembler.js';

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
    return { created: true, state: await assembleRoomState(room) };
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

  return assembleRoomState(room);
}
