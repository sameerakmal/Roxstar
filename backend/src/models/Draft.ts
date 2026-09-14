import { model, Schema, type HydratedDocument, type InferSchemaType } from 'mongoose';

import { DRAFT_EFFECT } from './enums.js';

const draftSchema = new Schema(
  {
    ownerUserId: {
      type: Schema.Types.ObjectId,
      ref: 'User',
      required: true,
    },
    name: {
      type: String,
      required: true,
      trim: true,
      minlength: 1,
      maxlength: 100,
    },
    durationMs: {
      type: Number,
      required: true,
      min: 0,
      validate: {
        validator: Number.isInteger,
        message: 'durationMs must be an integer number of milliseconds',
      },
    },
    effect: {
      type: String,
      enum: DRAFT_EFFECT,
      required: true,
      default: 'NONE',
    },
    // Where the recording lives. Local device path in this phase; a hosted URL once
    // drafts are shared to a room.
    fileLocation: {
      type: String,
      required: true,
      trim: true,
    },
  },
  { timestamps: true },
);

// Draft listing for a user, newest first.
draftSchema.index({ ownerUserId: 1, createdAt: -1 });

export type Draft = InferSchemaType<typeof draftSchema>;
export type DraftDocument = HydratedDocument<Draft>;

export const DraftModel = model('Draft', draftSchema);
