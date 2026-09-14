import { Router } from 'express';

import { createDraftSchema, getDrafts, postDraft } from '../controllers/draftController.js';
import { currentUser } from '../middleware/currentUser.js';
import { validateBody } from '../middleware/validateRequest.js';

// Mounted at /drafts.
export const draftRouter = Router();

draftRouter.use(currentUser);

draftRouter.post('/', validateBody(createDraftSchema), postDraft);
draftRouter.get('/', getDrafts);
