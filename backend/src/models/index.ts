import type { Model } from 'mongoose';

import { logger } from '../utils/logger.js';
import { DraftModel } from './Draft.js';
import { RoomModel } from './Room.js';
import { RoomDraftShareModel } from './RoomDraftShare.js';
import { RoomMemberModel } from './RoomMember.js';
import { SpinModel } from './Spin.js';
import { SpinEventModel } from './SpinEvent.js';
import { SpinParticipantModel } from './SpinParticipant.js';
import { UserModel } from './User.js';

export * from './enums.js';
export { DraftModel, type Draft, type DraftDocument } from './Draft.js';
export { RoomModel, type Room, type RoomDocument } from './Room.js';
export {
  RoomDraftShareModel,
  type RoomDraftShare,
  type RoomDraftShareDocument,
} from './RoomDraftShare.js';
export { RoomMemberModel, type RoomMember, type RoomMemberDocument } from './RoomMember.js';
export { SpinModel, type Spin, type SpinDocument } from './Spin.js';
export { SpinEventModel, type SpinEvent, type SpinEventDocument } from './SpinEvent.js';
export {
  SpinParticipantModel,
  type SpinParticipant,
  type SpinParticipantDocument,
} from './SpinParticipant.js';
export { UserModel, type User, type UserDocument } from './User.js';

// Importing this module registers every model with the Mongoose connection.
const allModels: Model<unknown>[] = [
  UserModel,
  RoomModel,
  RoomMemberModel,
  DraftModel,
  RoomDraftShareModel,
  SpinModel,
  SpinParticipantModel,
  SpinEventModel,
] as unknown as Model<unknown>[];

// Index creation is explicit rather than implicit: the connection sets
// autoIndex: false, so indexes are only built when this is called. The unique and
// partial indexes carry real invariants, so a silent failure to build them would
// silently remove the guarantees — hence the log line and the rethrow.
export async function syncAllIndexes(): Promise<void> {
  for (const currentModel of allModels) {
    await currentModel.syncIndexes();
  }
  logger.info({ models: allModels.length }, 'Model indexes synchronized');
}
