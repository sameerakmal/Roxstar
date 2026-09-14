import { Router } from 'express';

import { draftRouter } from './drafts.js';
import { healthRouter } from './health.js';
import { roomRouter } from './rooms.js';
import { spinRouter } from './spins.js';
import { userRouter } from './users.js';

export const router = Router();

router.use(healthRouter);
router.use('/users', userRouter);
router.use('/drafts', draftRouter);
router.use('/rooms', roomRouter);
router.use('/spins', spinRouter);
