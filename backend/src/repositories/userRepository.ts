import type { Types } from 'mongoose';

import { UserModel, type User } from '../models/index.js';

export type UserRecord = User & { _id: Types.ObjectId };

export async function createUser(displayName: string): Promise<UserRecord> {
  const created = await UserModel.create({ displayName });
  return created.toObject();
}

export async function findUserById(userId: Types.ObjectId): Promise<UserRecord | null> {
  return UserModel.findById(userId).lean<UserRecord>().exec();
}

export async function findUsersByIds(userIds: Types.ObjectId[]): Promise<UserRecord[]> {
  return UserModel.find({ _id: { $in: userIds } })
    .lean<UserRecord[]>()
    .exec();
}
