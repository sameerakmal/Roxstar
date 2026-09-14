import { Router } from 'express';

import { draftRouter } from './drafts.js';
import { healthRouter } from './health.js';
import { roomRouter } from './rooms.js';
import { userRouter } from './users.js';

export const router = Router();

router.use(healthRouter);
router.use('/users', userRouter);
router.use('/drafts', draftRouter);
router.use('/rooms', roomRouter);

// Spin routes are mounted here in Phase 4.
