import type { Types } from 'mongoose';

import {
  DuplicateKeyError,
  duplicateKeyIndexName,
  isDuplicateKeyError,
} from '../errors/RepositoryError.js';
import { RoomMemberModel, type ConnectionState, type RoomMember } from '../models/index.js';

export type RoomMemberRecord = RoomMember & { _id: Types.ObjectId };

// Inserts a JOINED membership. The unique partial index rejects a second concurrent
// join for the same user, so simultaneous joins cannot produce duplicate participants.
export async function joinRoom(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
  connectionState: ConnectionState = 'DISCONNECTED',
): Promise<RoomMemberRecord> {
  try {
    const created = await RoomMemberModel.create({ roomId, userId, connectionState });
    return created.toObject();
  } catch (error: unknown) {
    if (isDuplicateKeyError(error)) {
      throw new DuplicateKeyError(
        duplicateKeyIndexName(error),
        `User ${userId.toString()} is already an active member of room ${roomId.toString()}`,
      );
    }
    throw error;
  }
}

// Marks the active membership LEFT. Filtering on membershipState makes this idempotent:
// a repeated leave matches nothing and returns null rather than corrupting history.
export async function leaveRoom(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<RoomMemberRecord | null> {
  return RoomMemberModel.findOneAndUpdate(
    { roomId, userId, membershipState: 'JOINED' },
    { $set: { membershipState: 'LEFT', leftAt: new Date(), connectionState: 'DISCONNECTED' } },
    { new: true },
  )
    .lean<RoomMemberRecord>()
    .exec();
}

// Connection state moves independently of membership, so a transport drop never reads
// as a deliberate leave. This is what allows a reconnecting user to be restored.
export async function setConnectionState(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
  connectionState: ConnectionState,
): Promise<RoomMemberRecord | null> {
  return RoomMemberModel.findOneAndUpdate(
    { roomId, userId, membershipState: 'JOINED' },
    { $set: { connectionState } },
    { new: true },
  )
    .lean<RoomMemberRecord>()
    .exec();
}

export async function findActiveMembers(roomId: Types.ObjectId): Promise<RoomMemberRecord[]> {
  return RoomMemberModel.find({ roomId, membershipState: 'JOINED' })
    .sort({ joinedAt: 1 })
    .lean<RoomMemberRecord[]>()
    .exec();
}

export async function findActiveMembership(
  roomId: Types.ObjectId,
  userId: Types.ObjectId,
): Promise<RoomMemberRecord | null> {
  return RoomMemberModel.findOne({ roomId, userId, membershipState: 'JOINED' })
    .lean<RoomMemberRecord>()
    .exec();
}

export async function countActiveMembers(roomId: Types.ObjectId): Promise<number> {
  return RoomMemberModel.countDocuments({ roomId, membershipState: 'JOINED' }).exec();
}

// Full join/leave history for a room, which the append-only membership model preserves.
export async function findMembershipHistory(roomId: Types.ObjectId): Promise<RoomMemberRecord[]> {
  return RoomMemberModel.find({ roomId })
    .sort({ joinedAt: 1 })
    .lean<RoomMemberRecord[]>()
    .exec();
}
