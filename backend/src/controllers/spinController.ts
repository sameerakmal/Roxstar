import type { Request, Response } from 'express';
import type { Types } from 'mongoose';
import { z } from 'zod';

import { getCurrentUserId } from '../middleware/currentUser.js';
import { objectIdSchema, validatedParams } from '../middleware/validateRequest.js';
import * as spinService from '../services/spinService.js';

export const spinParamsSchema = z.object({ spinId: objectIdSchema });

type RoomParams = { roomId: Types.ObjectId };
type SpinParams = { spinId: Types.ObjectId };

export async function postSpin(req: Request, res: Response): Promise<void> {
  const { roomId } = validatedParams<RoomParams>(res);
  const state = await spinService.startSpin(roomId, getCurrentUserId(req));
  res.status(201).json(state);
}

export async function getSpin(req: Request, res: Response): Promise<void> {
  const { spinId } = validatedParams<SpinParams>(res);
  const state = await spinService.getSpinState(spinId, getCurrentUserId(req));
  res.status(200).json(state);
}
