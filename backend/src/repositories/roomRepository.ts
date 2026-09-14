import type { Types } from 'mongoose';

import { RoomModel, type Room, type RoomStatus } from '../models/index.js';

export type RoomRecord = Room & { _id: Types.ObjectId };

export async function createRoom(ownerUserId: Types.ObjectId): Promise<RoomRecord> {
  const created = await RoomModel.create({ ownerUserId });
  return created.toObject();
}

export async function findRoomById(roomId: Types.ObjectId): Promise<RoomRecord | null> {
  return RoomModel.findById(roomId).lean<RoomRecord>().exec();
}

export async function findRoomsByOwner(ownerUserId: Types.ObjectId): Promise<RoomRecord[]> {
  return RoomModel.find({ ownerUserId })
    .sort({ createdAt: -1 })
    .lean<RoomRecord[]>()
    .exec();
}

// Conditional update: the status filter makes the transition a compare-and-swap, so
// a room cannot be closed twice or reopened by a stale request. Returns null when the
// room was not in the expected state.
export async function transitionRoomStatus(
  roomId: Types.ObjectId,
  from: RoomStatus,
  to: RoomStatus,
): Promise<RoomRecord | null> {
  return RoomModel.findOneAndUpdate(
    { _id: roomId, status: from },
    { $set: { status: to } },
    { new: true },
  )
    .lean<RoomRecord>()
    .exec();
}
