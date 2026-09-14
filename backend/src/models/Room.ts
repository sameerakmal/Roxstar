import { model, Schema, type HydratedDocument, type InferSchemaType } from 'mongoose';

import { ROOM_STATUS } from './enums.js';

const roomSchema = new Schema(
  {
    ownerUserId: {
      type: Schema.Types.ObjectId,
      ref: 'User',
      required: true,
    },
    status: {
      type: String,
      enum: ROOM_STATUS,
      required: true,
      default: 'ACTIVE',
    },
  },
  { timestamps: true },
);

// Rooms owned by a user.
roomSchema.index({ ownerUserId: 1 });

export type Room = InferSchemaType<typeof roomSchema>;
export type RoomDocument = HydratedDocument<Room>;

export const RoomModel = model('Room', roomSchema);
