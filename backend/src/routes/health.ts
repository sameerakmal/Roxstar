import { Router } from 'express';

import { getHealth, getReady } from '../controllers/health.js';

export const healthRouter = Router();

healthRouter.get('/health', getHealth);
healthRouter.get('/ready', getReady);
