import mongoose from 'mongoose';

import { connectDatabase, disconnectDatabase } from '../../src/config/database.js';
import { syncAllIndexes } from '../../src/models/index.js';

// A dedicated database, never the development one, so a test run can never clear
// real data. Override with MONGODB_TEST_URI to point at another instance.
export const TEST_MONGODB_URI =
  process.env['MONGODB_TEST_URI'] ?? 'mongodb://127.0.0.1:27017/roxstar_test';

export async function connectTestDatabase(): Promise<void> {
  await connectDatabase(TEST_MONGODB_URI);
  // Indexes carry the invariants under test, so they must exist before any spec runs.
  await syncAllIndexes();
}

export async function disconnectTestDatabase(): Promise<void> {
  await mongoose.connection.dropDatabase();
  await disconnectDatabase();
}

// Clears documents but keeps indexes, so each test starts from an empty collection
// without paying to rebuild the indexes it is exercising.
export async function clearCollections(): Promise<void> {
  const { collections } = mongoose.connection;
  await Promise.all(
    Object.values(collections).map((collection) => collection.deleteMany({})),
  );
}
