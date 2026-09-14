import type { Request, Response } from 'express';
import type { Types } from 'mongoose';
import { z } from 'zod';

import { getCurrentUserId } from '../middleware/currentUser.js';
import { objectIdSchema, validatedBody, validatedParams } from '../middleware/validateRequest.js';
import { DRAFT_EFFECT } from '../models/index.js';
import * as draftService from '../services/draftService.js';

export const createDraftSchema = z.object({
  name: z.string().trim().min(1).max(100),
  durationMs: z.number().int().min(0),
  fileLocation: z.string().trim().min(1),
  effect: z.enum(DRAFT_EFFECT).optional(),
});

export const shareDraftSchema = z.object({ draftId: objectIdSchema });

type CreateDraftBody = z.infer<typeof createDraftSchema>;
type ShareDraftBody = { draftId: Types.ObjectId };
type RoomParams = { roomId: Types.ObjectId };

export async function postDraft(req: Request, res: Response): Promise<void> {
  const body = validatedBody<CreateDraftBody>(res);
  const draft = await draftService.createDraft(getCurrentUserId(req), body);
  res.status(201).json(draft);
}

export async function getDrafts(req: Request, res: Response): Promise<void> {
  const drafts = await draftService.listOwnDrafts(getCurrentUserId(req));
  res.status(200).json({ drafts });
}

// 201 on the first share, 200 when the draft is already shared with this room.
export async function postShareDraft(req: Request, res: Response): Promise<void> {
  const { roomId } = validatedParams<RoomParams>(res);
  const { draftId } = validatedBody<ShareDraftBody>(res);
  const outcome = await draftService.shareDraftWithRoom(roomId, draftId, getCurrentUserId(req));
  res.status(outcome.created ? 201 : 200).json(outcome.share);
}
