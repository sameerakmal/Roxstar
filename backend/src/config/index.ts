import { config as loadDotenv } from 'dotenv';
import { z } from 'zod';

loadDotenv({ quiet: true });

const environmentSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),
  MONGODB_URI: z.string().min(1, 'MONGODB_URI is required'),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace']).default('info'),
  // Seconds between eliminations. 5000 in production per the assessment; tests inject
  // a much smaller value so a full spin runs in milliseconds.
  SPIN_ELIMINATION_INTERVAL_MS: z.coerce.number().int().positive().default(5000),
});

export type AppConfig = {
  nodeEnv: 'development' | 'test' | 'production';
  port: number;
  mongodbUri: string;
  logLevel: 'fatal' | 'error' | 'warn' | 'info' | 'debug' | 'trace';
  spinEliminationIntervalMs: number;
  isProduction: boolean;
};

export function loadConfig(source: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = environmentSchema.safeParse(source);

  if (!parsed.success) {
    const problems = parsed.error.issues
      .map((issue) => `  - ${issue.path.join('.')}: ${issue.message}`)
      .join('\n');
    throw new Error(`Invalid environment configuration:\n${problems}`);
  }

  const env = parsed.data;

  return {
    nodeEnv: env.NODE_ENV,
    port: env.PORT,
    mongodbUri: env.MONGODB_URI,
    logLevel: env.LOG_LEVEL,
    spinEliminationIntervalMs: env.SPIN_ELIMINATION_INTERVAL_MS,
    isProduction: env.NODE_ENV === 'production',
  };
}
