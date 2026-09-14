import type {
  ConnectionState,
  DraftEffect,
  EliminationReason,
  MembershipState,
  RoomStatus,
  SpinParticipantStatus,
  SpinEventType,
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

export type SpinPlayerDto = {
  userId: string;
  displayName: string;
  status: SpinParticipantStatus;
  eliminationOrder: number | null;
  eliminationReason: EliminationReason | null;
};

export type ActiveSpinDto = {
  spinId: string;
  status: SpinStatus;
  startedAt: Date | null;
  participants: SpinPlayerDto[];
  remainingPlayers: SpinPlayerDto[];
  winner: SpinPlayerDto | null;
  // The highest event sequence the server has persisted for this spin. A client
  // applies incremental events only when they follow this, and otherwise adopts a
  // fresh snapshot — the stream is not assumed to be gapless.
  lastSequenceNumber: number;
};

export type SpinEventDto = {
  sequenceNumber: number;
  eventType: SpinEventType;
  payload: unknown;
  createdAt: Date;
};

// Returned by the spin endpoints: live state for a running spin, or the final result
// plus the persisted event sequence once it has finished.
export type SpinStateDto = {
  spinId: string;
  roomId: string;
  status: SpinStatus;
  startedAt: Date | null;
  completedAt: Date | null;
  startedByUserId: string | null;
  abortReason: string | null;
  participants: SpinPlayerDto[];
  remainingPlayers: SpinPlayerDto[];
  winner: SpinPlayerDto | null;
  lastSequenceNumber: number;
  events: SpinEventDto[];
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
