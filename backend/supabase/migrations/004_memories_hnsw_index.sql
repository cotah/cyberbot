-- Migration 004: replace the ivfflat index with HNSW.
--
-- The ivfflat index (lists=100) with the default ivfflat.probes=1 only scans a
-- single cluster, so on a small/growing memory store it misses most matches
-- (a fresh query vector falls into an empty/other list and returns nothing).
-- HNSW gives high recall out of the box and needs no training data, which fits
-- a personal assistant's memory table far better.

DROP INDEX IF EXISTS idx_memories_embedding;

CREATE INDEX IF NOT EXISTS idx_memories_embedding
  ON memories USING hnsw (embedding vector_cosine_ops);
