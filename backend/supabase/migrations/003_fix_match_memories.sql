-- Migration 003: make match_memories robust to the Supabase Python client.
--
-- PostgREST / supabase-py does not reliably coerce a value into a pgvector
-- argument (neither a raw list nor a text literal worked), which made the
-- function return no rows. Instead we accept a float8[] argument — which
-- PostgREST maps cleanly from a JSON number array — and cast it to vector
-- inside the function (pgvector supports array::vector).

DROP FUNCTION IF EXISTS match_memories(vector, float, int);
DROP FUNCTION IF EXISTS match_memories(vector, double precision, integer);
DROP FUNCTION IF EXISTS match_memories(text, double precision, integer);

CREATE OR REPLACE FUNCTION match_memories(
  query_embedding float8[],
  match_threshold float DEFAULT 0.3,
  match_count int DEFAULT 5
)
RETURNS TABLE (
  id UUID,
  content TEXT,
  category TEXT,
  language TEXT,
  similarity FLOAT
)
LANGUAGE plpgsql
AS $$
DECLARE
  q vector := query_embedding::vector;
BEGIN
  RETURN QUERY
  SELECT
    memories.id,
    memories.content,
    memories.category,
    memories.language,
    1 - (memories.embedding <=> q) AS similarity
  FROM memories
  WHERE 1 - (memories.embedding <=> q) > match_threshold
  ORDER BY memories.embedding <=> q
  LIMIT match_count;
END;
$$;
