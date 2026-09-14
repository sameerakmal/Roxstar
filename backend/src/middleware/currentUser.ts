import type { NextFunction, Request, Response } from 'express';
import { Types } from 'mongoose';

import { AppError } from '../errors/AppError.js';
import { requireUser } from '../services/userService.js';

// ---------------------------------------------------------------------------
// ASSESSMENT / DEMO IDENTITY ONLY — THIS IS NOT AUTHENTICATION.
//
// The caller states who they are with an X-User-Id header and the server believes
// them. There is no credential, no signature and no session, so any client can act
// as any user. The assessment does not ask for authentication and none of its scoring
// items cover it, so no JWT/session/OAuth infrastructure is introduced here.
//
// A production system would replace this middleware with a real authentication step
// that derives the user from a verified credential; everything downstream already
// reads identity from req.currentUserId and would not need to change.
// ---------------------------------------------------------------------------

export const USER_ID_HEADER = 'x-user-id';

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      currentUserId?: Types.ObjectId;
    }
  }
}

export function currentUser(req: Request, _res: Response, next: NextFunction): void {
  const header = req.get(USER_ID_HEADER);

  if (header === undefined || header.trim() === '') {
    next(
      new AppError(401, 'MISSING_USER_ID', `The ${USER_ID_HEADER} header is required`),
    );
    return;
  }

  if (!Types.ObjectId.isValid(header)) {
    next(
      new AppError(400, 'INVALID_USER_ID', `The ${USER_ID_HEADER} header is not a valid id`),
    );
    return;
  }

  const userId = new Types.ObjectId(header);

  // An unknown id is rejected here so no downstream write can reference a user that
  // does not exist.
  requireUser(userId)
    .then(() => {
      req.currentUserId = userId;
      next();
    })
    .catch(next);
}

// Downstream handlers run only behind currentUser, so the id is always present.
export function getCurrentUserId(req: Request): Types.ObjectId {
  const userId = req.currentUserId;
  if (userId === undefined) {
    throw new AppError(500, 'INTERNAL_ERROR', 'currentUser middleware did not run');
  }
  return userId;
}
