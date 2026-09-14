import type { Request, Response } from 'express';
import type { Types } from 'mongoose';
import { z } from 'zod';

import { getCurrentUserId } from '../middleware/currentUser.js';
import { objectIdSchema, validatedParams } from '../middleware/validateRequest.js';
import * as roomService from '../services/roomService.js';

export const roomParamsSchema = z.object({ roomId: objectIdSchema });

type RoomParams = { roomId: Types.ObjectId };

export async function postRoom(req: Request, res: Response): Promise<void> {
  const state = await roomService.createRoom(getCurrentUserId(req));
  res.status(201).json(state);
}

export async function getRoom(req: Request, res: Response): Promise<void> {
  const { roomId } = validatedParams<RoomParams>(res);
  const state = await roomService.getRoomState(roomId, getCurrentUserId(req));
  res.status(200).json(state);
}

// 201 when this request created the membership, 200 when the caller was already an
// active member — the idempotent repeat.
export async function postJoin(req: Request, res: Response): Promise<void> {
  const { roomId } = validatedParams<RoomParams>(res);
  const outcome = await roomService.joinRoom(roomId, getCurrentUserId(req));
  res.status(outcome.created ? 201 : 200).json(outcome.state);
}

export async function postLeave(req: Request, res: Response): Promise<void> {
  const { roomId } = validatedParams<RoomParams>(res);
  const state = await roomService.leaveRoom(roomId, getCurrentUserId(req));
  res.status(200).json(state);
}
