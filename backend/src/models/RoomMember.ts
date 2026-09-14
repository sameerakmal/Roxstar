import { model, Schema, type HydratedDocument, type InferSchemaType } from 'mongoose';

import { CONNECTION_STATE, MEMBERSHIP_STATE } from './enums.js';

// Membership rows are append-only: leaving marks the row LEFT, and rejoining inserts
// a new row. The partial unique index below permits many LEFT rows per (room, user)
// while allowing at most one JOINED row, which preserves join/leave history.
const roomMemberSchema = new Schema(
  {
    roomId: {
      type: Schema.Types.ObjectId,
      ref: 'Room',
      required: true,
    },
    userId: {
      type: Schema.Types.ObjectId,
      ref: 'User',
      required: true,
    },
    // Deliberate intent: did the user join or leave.
    membershipState: {
      type: String,
      enum: MEMBERSHIP_STATE,
      required: true,
      default: 'JOINED',
    },
    // Transport reality: is a socket currently attached. Kept separate from
    // membershipState so a dropped connection never looks like a deliberate leave,
    // which is what makes reconnect recovery possible.
    connectionState: {
      type: String,
      enum: CONNECTION_STATE,
      required: true,
      default: 'DISCONNECTED',
    },
    joinedAt: {
      type: Date,
      required: true,
      default: (): Date => new Date(),
    },
    leftAt: {
      type: Date,
      default: null,
    },
  },
  { timestamps: true },
);

roomMemberSchema.pre('validate', function (next) {
  if (this.membershipState === 'LEFT' && this.leftAt === null) {
    this.invalidate('leftAt', 'leftAt is required when membershipState is LEFT');
  }
  if (this.membershipState === 'JOINED' && this.leftAt !== null) {
    this.invalidate('leftAt', 'leftAt must be null when membershipState is JOINED');
  }
  next();
});

// At most one active membership per user per room.
roomMemberSchema.index(
  { roomId: 1, userId: 1 },
  {
    unique: true,
    partialFilterExpression: { membershipState: 'JOINED' },
    name: 'uniq_active_membership',
  },
);

// Participant list for room state and broadcast fan-out — the hottest read.
roomMemberSchema.index({ roomId: 1, membershipState: 1 });

export type RoomMember = InferSchemaType<typeof roomMemberSchema>;
export type RoomMemberDocument = HydratedDocument<RoomMember>;

export const RoomMemberModel = model('RoomMember', roomMemberSchema);
