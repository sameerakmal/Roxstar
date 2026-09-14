import { model, Schema, type HydratedDocument, type InferSchemaType } from 'mongoose';

import { ELIMINATION_REASON, SPIN_PARTICIPANT_STATUS } from './enums.js';

const spinParticipantSchema = new Schema(
  {
    spinId: {
      type: Schema.Types.ObjectId,
      ref: 'Spin',
      required: true,
    },
    userId: {
      type: Schema.Types.ObjectId,
      ref: 'User',
      required: true,
    },
    status: {
      type: String,
      enum: SPIN_PARTICIPANT_STATUS,
      required: true,
      default: 'ELIGIBLE',
    },
    eliminationOrder: {
      type: Number,
      default: null,
      min: 1,
      validate: {
        validator: (value: number | null): boolean => value === null || Number.isInteger(value),
        message: 'eliminationOrder must be an integer',
      },
    },
    eliminatedAt: {
      type: Date,
      default: null,
    },
    // TIMER = removed by a scheduled 5-second tick. LEFT = removed because the user
    // left the room mid-spin. Recovery counts only TIMER against the schedule.
    eliminationReason: {
      type: String,
      enum: ELIMINATION_REASON,
      default: null,
    },
  },
  { timestamps: true },
);

spinParticipantSchema.pre('validate', function (next) {
  const isEliminated = this.status === 'ELIMINATED';

  if (isEliminated && this.eliminationOrder === null) {
    this.invalidate('eliminationOrder', 'An ELIMINATED participant must record an order');
  }
  if (isEliminated && this.eliminatedAt === null) {
    this.invalidate('eliminatedAt', 'An ELIMINATED participant must record a timestamp');
  }
  if (isEliminated && this.eliminationReason === null) {
    this.invalidate('eliminationReason', 'An ELIMINATED participant must record a reason');
  }
  if (!isEliminated && this.eliminationReason !== null) {
    this.invalidate('eliminationReason', 'Only an ELIMINATED participant may record a reason');
  }
  if (!isEliminated && this.eliminationOrder !== null) {
    this.invalidate('eliminationOrder', 'Only an ELIMINATED participant may record an order');
  }
  if (!isEliminated && this.eliminatedAt !== null) {
    this.invalidate('eliminatedAt', 'Only an ELIMINATED participant may record a timestamp');
  }
  next();
});

// A user appears at most once per spin.
spinParticipantSchema.index({ spinId: 1, userId: 1 }, { unique: true, name: 'uniq_spin_user' });

// Elimination order is unique within a spin.
//
// The filter is `$type: 'number'`, NOT `$exists: true`. In MongoDB `$exists` also
// matches an explicit null, so an $exists filter would index every not-yet-eliminated
// participant under a null key and allow only ONE of them per spin — breaking every
// spin at the second participant. `$type: 'number'` indexes only real orders.
spinParticipantSchema.index(
  { spinId: 1, eliminationOrder: 1 },
  {
    unique: true,
    partialFilterExpression: { eliminationOrder: { $type: 'number' } },
    name: 'uniq_elimination_order',
  },
);

// Selecting the next active participant on each elimination tick.
spinParticipantSchema.index({ spinId: 1, status: 1 });

export type SpinParticipant = InferSchemaType<typeof spinParticipantSchema>;
export type SpinParticipantDocument = HydratedDocument<SpinParticipant>;

export const SpinParticipantModel = model('SpinParticipant', spinParticipantSchema);
