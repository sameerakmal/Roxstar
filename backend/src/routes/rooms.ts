import { Router } from 'express';

import { postShareDraft, shareDraftSchema } from '../controllers/draftController.js';
import {
  getRoom,
  postJoin,
  postLeave,
  postRoom,
  roomParamsSchema,
} from '../controllers/roomController.js';
import { currentUser } from '../middleware/currentUser.js';
import { validateBody, validateParams } from '../middleware/validateRequest.js';

// Mounted at /rooms. Router-level middleware only runs for paths under that prefix,
// so unmatched application routes still reach the 404 handler.
export const roomRouter = Router();

// Every room operation acts on behalf of an identified caller.
roomRouter.use(currentUser);

roomRouter.post('/', postRoom);
roomRouter.get('/:roomId', validateParams(roomParamsSchema), getRoom);
roomRouter.post('/:roomId/join', validateParams(roomParamsSchema), postJoin);
roomRouter.post('/:roomId/leave', validateParams(roomParamsSchema), postLeave);
roomRouter.post(
  '/:roomId/drafts',
  validateParams(roomParamsSchema),
  validateBody(shareDraftSchema),
  postShareDraft,
);
