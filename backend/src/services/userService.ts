import type { Types } from 'mongoose';

import { UserNotFoundError } from '../errors/DomainError.js';
import { userRepository } from '../repositories/index.js';
import type { UserDto } from './dto.js';
import type { UserRecord } from '../repositories/userRepository.js';

function toUserDto(user: UserRecord): UserDto {
  return {
    id: user._id.toString(),
    displayName: user.displayName,
    createdAt: user.createdAt,
  };
}

export async function createUser(displayName: string): Promise<UserDto> {
  const user = await userRepository.createUser(displayName);
  return toUserDto(user);
}

// Identity check behind the X-User-Id header. A syntactically valid id that matches
// no user is rejected here rather than being allowed to create orphaned references.
export async function requireUser(userId: Types.ObjectId): Promise<UserDto> {
  const user = await userRepository.findUserById(userId);
  if (user === null) {
    throw new UserNotFoundError(userId.toString());
  }
  return toUserDto(user);
}
