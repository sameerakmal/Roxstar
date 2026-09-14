import { Router } from 'express';

import { createUserSchema, postUser } from '../controllers/userController.js';
import { validateBody } from '../middleware/validateRequest.js';

// Mounted at /users. Identity bootstrap, deliberately unauthenticated: see the note
// in middleware/currentUser.ts about the assessment identity model.
export const userRouter = Router();

userRouter.post('/', validateBody(createUserSchema), postUser);
