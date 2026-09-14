import { model, Schema, type HydratedDocument, type InferSchemaType } from 'mongoose';

// A Draft is owned by a user, but sharing is room-scoped: this association records
// that a draft was shared into a room, and by whom.
const roomDraftShareSchema = new Schema(
  {
    roomId: {
      type: Schema.Types.ObjectId,
      ref: 'Room',
      required: true,
    },
    draftId: {
      type: Schema.Types.ObjectId,
      ref: 'Draft',
      required: true,
    },
    sharedByUserId: {
      type: Schema.Types.ObjectId,
      ref: 'User',
      required: true,
    },
    sharedAt: {
      type: Date,
      required: true,
      default: (): Date => new Date(),
    },
  },
  { timestamps: true },
);

// The same draft is shared into the same room at most once, so a repeated share
// request is a no-op rather than a duplicate entry in the room's draft list.
roomDraftShareSchema.index(
  { roomId: 1, draftId: 1 },
  { unique: true, name: 'uniq_room_draft_share' },
);

// Shared-draft list for a room snapshot, newest first.
roomDraftShareSchema.index({ roomId: 1, sharedAt: -1 });

export type RoomDraftShare = InferSchemaType<typeof roomDraftShareSchema>;
export type RoomDraftShareDocument = HydratedDocument<RoomDraftShare>;

export const RoomDraftShareModel = model('RoomDraftShare', roomDraftShareSchema);
