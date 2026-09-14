// Each enum is declared once as a const tuple and reused for both the Mongoose
// `enum` validator and the TypeScript union, so the two cannot drift apart.

export const ROOM_STATUS = ['ACTIVE', 'CLOSED'] as const;
export type RoomStatus = (typeof ROOM_STATUS)[number];

export const MEMBERSHIP_STATE = ['JOINED', 'LEFT'] as const;
export type MembershipState = (typeof MEMBERSHIP_STATE)[number];

export const CONNECTION_STATE = ['CONNECTED', 'DISCONNECTED'] as const;
export type ConnectionState = (typeof CONNECTION_STATE)[number];

export const DRAFT_EFFECT = ['NONE', 'ECHO', 'REVERB', 'PITCH_SHIFT'] as const;
export type DraftEffect = (typeof DRAFT_EFFECT)[number];

export const SPIN_STATUS = ['WAITING', 'RUNNING', 'COMPLETED', 'ABORTED'] as const;
export type SpinStatus = (typeof SPIN_STATUS)[number];

// Statuses that mean "this spin still occupies the room". The unique partial index
// on Spin is built from exactly this list, so adding a status here automatically
// widens the single-active-spin constraint.
export const ACTIVE_SPIN_STATUSES = ['WAITING', 'RUNNING'] as const;
export type ActiveSpinStatus = (typeof ACTIVE_SPIN_STATUSES)[number];

export const SPIN_PARTICIPANT_STATUS = ['ELIGIBLE', 'ACTIVE', 'ELIMINATED', 'WINNER'] as const;
export type SpinParticipantStatus = (typeof SPIN_PARTICIPANT_STATUS)[number];

// spin_aborted is persisted for audit and restart recovery only; the assessment's
// mandatory broadcast events are the other three.
export const SPIN_EVENT_TYPE = [
  'spin_started',
  'user_eliminated',
  'winner_announced',
  'spin_aborted',
] as const;
export type SpinEventType = (typeof SPIN_EVENT_TYPE)[number];
