import express, { type Express } from 'express';

import { errorHandler } from './middleware/errorHandler.js';
import { notFoundHandler } from './middleware/notFound.js';
import { requestLogger } from './middleware/requestLogger.js';
import { router } from './routes/index.js';

// Builds the Express app only. It opens no database connection and binds no port,
// so tests can exercise it without any external dependency.
export function createApp(): Express {
  const app = express();

  app.use(requestLogger);
  app.use(express.json());

  app.use(router);

  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}
