import { Router } from 'express';

import { getSpin, spinParamsSchema } from '../controllers/spinController.js';
import { currentUser } from '../middleware/currentUser.js';
import { validateParams } from '../middleware/validateRequest.js';

// Mounted at /spins.
export const spinRouter = Router();

spinRouter.use(currentUser);

spinRouter.get('/:spinId', validateParams(spinParamsSchema), getSpin);
