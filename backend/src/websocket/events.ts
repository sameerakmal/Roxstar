import type {
  ParticipantDto,
  SharedDraftDto,
  SpinPlayerDto,
  RoomStateDto,
} from '../services/dto.js';

// Server -> client event names. The seven mandatory events from the assessment.
export const ROOM_EVENTS = {
  userJoined: 'user_joined',
  userLeft: 'user_left',
  draftShared: 'draft_shared',
  spinStarted: 'spin_started',
  userEliminated: 'user_eliminated',
  winnerAnnounced: 'winner_announced',
  roomState: 'room_state',
} as const;

// Client -> server.
export const CLIENT_EVENTS = {
  joinRoom: 'join_room',
  leaveRoom: 'leave_room',
} as const;

export type LeaveReason = 'LEFT' | 'DISCONNECTED';

export type UserJoinedPayload = {
  roomId: string;
  user: { userId: string; displayName: string };
  participants: ParticipantDto[];
};

// One event covers both departures. `reason` distinguishes them: LEFT ends membership,
// DISCONNECTED leaves membership JOINED so the user can reconnect.
export type UserLeftPayload = {
  roomId: string;
  user: { userId: string; displayName: string };
  reason: LeaveReason;
  participants: ParticipantDto[];
};

export type DraftSharedPayload = {
  roomId: string;
  draft: SharedDraftDto;
};

export type SpinStartedPayload = {
  roomId: string;
  spinId: string;
  status: 'RUNNING';
  startedAt: Date;
  eligiblePlayers: SpinPlayerDto[];
  remainingPlayers: SpinPlayerDto[];
  sequenceNumber: number;
};

export type UserEliminatedPayload = {
  roomId: string;
  spinId: string;
  eliminatedUser: SpinPlayerDto;
  eliminationOrder: number;
  remainingPlayers: SpinPlayerDto[];
  sequenceNumber: number;
};

export type WinnerAnnouncedPayload = {
  roomId: string;
  spinId: string;
  status: 'COMPLETED';
  winner: SpinPlayerDto;
  completedAt: Date;
  sequenceNumber: number;
};

export type RoomStatePayload = RoomStateDto;
