import { model, Schema, type HydratedDocument, type InferSchemaType } from 'mongoose';

import { ACTIVE_SPIN_STATUSES, SPIN_STATUS } from './enums.js';

const spinSchema = new Schema(
  {
    roomId: {
      type: Schema.Types.ObjectId,
      ref: 'Room',
      required: true,
    },
    status: {
      type: String,
      enum: SPIN_STATUS,
      required: true,
      default: 'WAITING',
    },
    startedAt: {
      type: Date,
      default: null,
    },
    completedAt: {
      type: Date,
      default: null,
    },
    winnerUserId: {
      type: Schema.Types.ObjectId,
      ref: 'User',
      default: null,
    },
  },
  { timestamps: true },
);

spinSchema.pre('validate', function (next) {
  if (this.status === 'COMPLETED' && this.winnerUserId === null) {
    this.invalidate('winnerUserId', 'A COMPLETED spin must record a winner');
  }
  if (this.status !== 'COMPLETED' && this.winnerUserId !== null) {
    this.invalidate('winnerUserId', 'Only a COMPLETED spin may record a winner');
  }
  next();
});

// THE load-bearing constraint (assessment C1: "only one active spin may exist in a
// room"). Enforced by the database, not by a read-then-write check in the service
// layer: two concurrent start requests cannot both pass, because the uniqueness
// decision happens inside a single atomic insert. The loser receives a duplicate-key
// error (11000), which the repository translates into ActiveSpinExistsError.
//
// Terminal spins (COMPLETED / ABORTED) fall outside the filter, so a room accumulates
// unlimited spin history while never holding two active spins at once.
spinSchema.index(
  { roomId: 1 },
  {
    unique: true,
    partialFilterExpression: { status: { $in: ACTIVE_SPIN_STATUSES } },
    name: 'uniq_active_spin_per_room',
  },
);

// Active-spin lookup on every start request and room-state fetch.
spinSchema.index({ roomId: 1, status: 1 });

export type Spin = InferSchemaType<typeof spinSchema>;
export type SpinDocument = HydratedDocument<Spin>;

export const SpinModel = model('Spin', spinSchema);
