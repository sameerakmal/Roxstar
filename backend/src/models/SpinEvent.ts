import { model, Schema, type HydratedDocument, type InferSchemaType } from 'mongoose';

import { SPIN_EVENT_TYPE } from './enums.js';

// Append-only audit log. Events are persisted before they are broadcast, so the
// stored sequence, the broadcast stream and the Get Spin Result response are the
// same sequence. Documents are never updated.
const spinEventSchema = new Schema(
  {
    spinId: {
      type: Schema.Types.ObjectId,
      ref: 'Spin',
      required: true,
    },
    sequenceNumber: {
      type: Number,
      required: true,
      min: 1,
      validate: {
        validator: Number.isInteger,
        message: 'sequenceNumber must be an integer',
      },
    },
    eventType: {
      type: String,
      enum: SPIN_EVENT_TYPE,
      required: true,
    },
    payload: {
      type: Schema.Types.Mixed,
      required: true,
    },
  },
  { timestamps: true },
);

// Ordered event replay for Get Spin Result and reconnect recovery. Uniqueness makes
// persist-then-broadcast safely retryable: a duplicate event cannot be appended twice.
spinEventSchema.index(
  { spinId: 1, sequenceNumber: 1 },
  { unique: true, name: 'uniq_spin_sequence' },
);

export type SpinEvent = InferSchemaType<typeof spinEventSchema>;
export type SpinEventDocument = HydratedDocument<SpinEvent>;

export const SpinEventModel = model('SpinEvent', spinEventSchema);
