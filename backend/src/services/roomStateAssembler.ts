import type { Types } from 'mongoose';

import {
  draftRepository,
  roomMemberRepository,
  roomRepository,
  spinRepository,
  userRepository,
} from '../repositories/index.js';
import type { RoomRecord } from '../repositories/roomRepository.js';
import type { ActiveSpinDto, ParticipantDto, RoomDto, RoomStateDto, SharedDraftDto } from './dto.js';
import { buildActiveSpinDto } from './spinProjection.js';

export function toRoomDto(room: RoomRecord): RoomDto {
  return {
    id: room._id.toString(),
    status: room.status,
    ownerUserId: room.ownerUserId.toString(),
    createdAt: room.createdAt,
    updatedAt: room.updatedAt,
  };
}

async function assembleParticipants(roomId: Types.ObjectId): Promise<ParticipantDto[]> {
  const members = await roomMemberRepository.findActiveMembers(roomId);
  if (members.length === 0) {
    return [];
  }

  // One lookup for every participant's profile rather than one per member.
  const users = await userRepository.findUsersByIds(members.map((member) => member.userId));
  const displayNames = new Map(users.map((user) => [user._id.toString(), user.displayName]));

  return members.map((member) => ({
    userId: member.userId.toString(),
    displayName: displayNames.get(member.userId.toString()) ?? 'Unknown user',
    membershipState: member.membershipState,
    connectionState: member.connectionState,
    joinedAt: member.joinedAt,
  }));
}

async function assembleSharedDrafts(roomId: Types.ObjectId): Promise<SharedDraftDto[]> {
  const shares = await draftRepository.findSharedDrafts(roomId);
  if (shares.length === 0) {
    return [];
  }

  const drafts = await draftRepository.findDraftsByIds(shares.map((share) => share.draftId));
  const draftsById = new Map(drafts.map((draft) => [draft._id.toString(), draft]));

  // A share whose draft has since been deleted is skipped rather than reported with
  // empty metadata.
  return shares.flatMap((share) => {
    const draft = draftsById.get(share.draftId.toString());
    if (draft === undefined) {
      return [];
    }
    return [
      {
        draftId: draft._id.toString(),
        name: draft.name,
        durationMs: draft.durationMs,
        effect: draft.effect,
        fileLocation: draft.fileLocation,
        sharedByUserId: share.sharedByUserId.toString(),
        sharedAt: share.sharedAt,
      },
    ];
  });
}

async function assembleActiveSpin(roomId: Types.ObjectId): Promise<ActiveSpinDto | null> {
  const spin = await spinRepository.findActiveSpin(roomId);
  if (spin === null) {
    return null;
  }

  return buildActiveSpinDto(spin);
}

// Builds the authoritative room snapshot. Kept separate from roomService so the
// projection logic lives in one place and is reused by every endpoint that returns
// room state — and later by the WebSocket room_state event.
export async function assembleRoomState(room: RoomRecord): Promise<RoomStateDto> {
  const [participants, sharedDrafts, activeSpin] = await Promise.all([
    assembleParticipants(room._id),
    assembleSharedDrafts(room._id),
    assembleActiveSpin(room._id),
  ]);

  return { room: toRoomDto(room), participants, sharedDrafts, activeSpin };
}

// Convenience for callers that hold only an id — socket handlers and the spin engine,
// which broadcast state without having loaded the room document themselves.
export async function assembleRoomStateById(
  roomId: Types.ObjectId,
): Promise<RoomStateDto | null> {
  const room = await roomRepository.findRoomById(roomId);
  if (room === null) {
    return null;
  }
  return assembleRoomState(room);
}

export async function assembleParticipantDtos(
  roomId: Types.ObjectId,
): Promise<ParticipantDto[]> {
  return assembleParticipants(roomId);
}
