import type { Types } from 'mongoose';

import {
  DraftNotFoundError,
  DraftNotOwnedError,
  RoomClosedError,
  RoomNotFoundError,
} from '../errors/DomainError.js';
import { DuplicateKeyError } from '../errors/RepositoryError.js';
import type { DraftEffect } from '../models/index.js';
import { draftRepository, roomRepository } from '../repositories/index.js';
import type { DraftRecord } from '../repositories/draftRepository.js';
import { ROOM_EVENTS } from '../websocket/events.js';
import type { DraftDto, SharedDraftDto } from './dto.js';
import { publishToRoom } from './eventPublisher.js';
import { requireActiveMembership } from './roomService.js';

export type CreateDraftInput = {
  name: string;
  durationMs: number;
  fileLocation: string;
  effect?: DraftEffect;
};

export type ShareDraftOutcome = {
  created: boolean;
  share: SharedDraftDto;
};

function toDraftDto(draft: DraftRecord): DraftDto {
  return {
    id: draft._id.toString(),
    ownerUserId: draft.ownerUserId.toString(),
    name: draft.name,
    durationMs: draft.durationMs,
    effect: draft.effect,
    fileLocation: draft.fileLocation,
    createdAt: draft.createdAt,
  };
}

export async function createDraft(
  ownerUserId: Types.ObjectId,
  input: CreateDraftInput,
): Promise<DraftDto> {
  const draft = await draftRepository.createDraft({ ownerUserId, ...input });
  return toDraftDto(draft);
}

export async function listOwnDrafts(ownerUserId: Types.ObjectId): Promise<DraftDto[]> {
  const drafts = await draftRepository.findDraftsByOwner(ownerUserId);
  return drafts.map(toDraftDto);
}

// A valid share requires three things: the room exists and is open, the caller is an
// active member of it, and the caller owns the draft (TASKS RM-3 rejects sharing a
// draft you do not own).
//
// Idempotent like join: re-sharing returns the existing share with `created: false`,
// and a lost concurrent race arrives as DuplicateKeyError and takes the same path.
export async function shareDraftWithRoom(
  roomId: Types.ObjectId,
  draftId: Types.ObjectId,
  callerUserId: Types.ObjectId,
): Promise<ShareDraftOutcome> {
  const room = await roomRepository.findRoomById(roomId);
  if (room === null) {
    throw new RoomNotFoundError(roomId.toString());
  }
  if (room.status !== 'ACTIVE') {
    throw new RoomClosedError(roomId.toString());
  }

  await requireActiveMembership(roomId, callerUserId);

  const draft = await draftRepository.findDraftById(draftId);
  if (draft === null) {
    throw new DraftNotFoundError(draftId.toString());
  }
  if (draft.ownerUserId.toString() !== callerUserId.toString()) {
    throw new DraftNotOwnedError(draftId.toString());
  }

  const toDto = (sharedAt: Date, sharedByUserId: Types.ObjectId): SharedDraftDto => ({
    draftId: draft._id.toString(),
    name: draft.name,
    durationMs: draft.durationMs,
    effect: draft.effect,
    fileLocation: draft.fileLocation,
    sharedByUserId: sharedByUserId.toString(),
    sharedAt,
  });

  const existing = await draftRepository.findShare(roomId, draftId);
  if (existing !== null) {
    return { created: false, share: toDto(existing.sharedAt, existing.sharedByUserId) };
  }

  try {
    const share = await draftRepository.shareDraftWithRoom(roomId, draftId, callerUserId);
    const dto = toDto(share.sharedAt, share.sharedByUserId);
    publishToRoom(roomId.toString(), ROOM_EVENTS.draftShared, {
      roomId: roomId.toString(),
      draft: dto,
    });
    return { created: true, share: dto };
  } catch (error: unknown) {
    if (error instanceof DuplicateKeyError) {
      const winner = await draftRepository.findShare(roomId, draftId);
      if (winner !== null) {
        return { created: false, share: toDto(winner.sharedAt, winner.sharedByUserId) };
      }
    }
    throw error;
  }
}
