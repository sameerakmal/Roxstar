import type {
  ConnectionState,
  DraftEffect,
  MembershipState,
  RoomStatus,
  SpinStatus,
} from '../models/index.js';

// Response shapes. Mongoose internals (_id, __v) are deliberately absent: every
// identifier is exposed as a string `id` field so the wire format stays independent
// of the persistence layer.

export type UserDto = {
  id: string;
  displayName: string;
  createdAt: Date;
};

export type DraftDto = {
  id: string;
  ownerUserId: string;
  name: string;
  durationMs: number;
  effect: DraftEffect;
  fileLocation: string;
  createdAt: Date;
};

export type ParticipantDto = {
  userId: string;
  displayName: string;
  membershipState: MembershipState;
  connectionState: ConnectionState;
  joinedAt: Date;
};

export type SharedDraftDto = {
  draftId: string;
  name: string;
  durationMs: number;
  effect: DraftEffect;
  fileLocation: string;
  sharedByUserId: string;
  sharedAt: Date;
};

// Shape reserved for the Phase 4 spin engine. Phase 3 reports an active spin's
// identity and status only; participants, remaining players and the event sequence
// number are added when the engine exists.
export type ActiveSpinDto = {
  spinId: string;
  status: SpinStatus;
  startedAt: Date | null;
};

export type RoomDto = {
  id: string;
  status: RoomStatus;
  ownerUserId: string;
  createdAt: Date;
  updatedAt: Date;
};

export type RoomStateDto = {
  room: RoomDto;
  participants: ParticipantDto[];
  sharedDrafts: SharedDraftDto[];
  activeSpin: ActiveSpinDto | null;
};
