import pino from 'pino';

// 'silent' is a valid pino level used by the test config, so it is accepted here
// even though loadConfig() does not offer it as an application setting.
const PINO_LEVELS = ['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'] as const;

// Reads LOG_LEVEL directly rather than through loadConfig() so that importing the
// Express app never requires a fully valid environment (tests import app.ts alone).
// An unrecognised value falls back to 'info' instead of throwing: pino is constructed
// at module load, before loadConfig() runs, and a bad level must not pre-empt the
// readable validation error that loadConfig() raises for it.
function resolveLevel(value: string | undefined): string {
  return value !== undefined && (PINO_LEVELS as readonly string[]).includes(value) ? value : 'info';
}

export const logger = pino({
  level: resolveLevel(process.env['LOG_LEVEL']),
  redact: {
    paths: ['req.headers.authorization', 'req.headers.cookie'],
    remove: true,
  },
});
