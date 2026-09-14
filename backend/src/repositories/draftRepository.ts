import type { Types } from 'mongoose';

import {
  DuplicateKeyError,
  duplicateKeyIndexName,
  isDuplicateKeyError,
} from '../errors/RepositoryError.js';
import {
  DraftModel,
  RoomDraftShareModel,
  type Draft,
  type DraftEffect,
  type RoomDraftShare,
} from '../models/index.js';

export type DraftRecord = Draft & { _id: Types.ObjectId };
export type RoomDraftShareRecord = RoomDraftShare & { _id: Types.ObjectId };

export type CreateDraftInput = {
  ownerUserId: Types.ObjectId;
  name: string;
  durationMs: number;
  fileLocation: string;
  effect?: DraftEffect;
};

export async function createDraft(input: CreateDraftInput): Promise<DraftRecord> {
  const created = await DraftModel.create(input);
  return created.toObject();
}

export async function findDraftById(draftId: Types.ObjectId): Promise<DraftRecord | null> {
  return DraftModel.findById(draftId).lean<DraftRecord>().exec();
}

export async function findDraftsByOwner(ownerUserId: Types.ObjectId): Promise<DraftRecord[]> {
  return DraftModel.find({ ownerUserId })
    .sort({ createdAt: -1 })
    .lean<DraftRecord[]>()
    .exec();
}

export async function deleteDraft(draftId: Types.ObjectId): Promise<boolean> {
  const result = await DraftModel.deleteOne({ _id: draftId }).exec();
  return result.deletedCount === 1;
}

// Sharing the same draft into the same room twice is rejected by the unique index,
// so a repeated share cannot duplicate the room's draft list.
export async function shareDraftWithRoom(
  roomId: Types.ObjectId,
  draftId: Types.ObjectId,
  sharedByUserId: Types.ObjectId,
): Promise<RoomDraftShareRecord> {
  try {
    const created = await RoomDraftShareModel.create({ roomId, draftId, sharedByUserId });
    return created.toObject();
  } catch (error: unknown) {
    if (isDuplicateKeyError(error)) {
      throw new DuplicateKeyError(
        duplicateKeyIndexName(error),
        `Draft ${draftId.toString()} is already shared with room ${roomId.toString()}`,
      );
    }
    throw error;
  }
}

// Hydrates draft metadata for the drafts shared into a room, in one query rather
// than one lookup per share.
export async function findDraftsByIds(draftIds: Types.ObjectId[]): Promise<DraftRecord[]> {
  return DraftModel.find({ _id: { $in: draftIds } })
    .lean<DraftRecord[]>()
    .exec();
}

// Lets the service return the existing share for an idempotent repeat instead of
// surfacing a duplicate-key failure.
export async function findShare(
  roomId: Types.ObjectId,
  draftId: Types.ObjectId,
): Promise<RoomDraftShareRecord | null> {
  return RoomDraftShareModel.findOne({ roomId, draftId })
    .lean<RoomDraftShareRecord>()
    .exec();
}

export async function findSharedDrafts(roomId: Types.ObjectId): Promise<RoomDraftShareRecord[]> {
  return RoomDraftShareModel.find({ roomId })
    .sort({ sharedAt: -1 })
    .lean<RoomDraftShareRecord[]>()
    .exec();
}
