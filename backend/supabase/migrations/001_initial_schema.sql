-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Conversations table
-- Stores full conversation history per session
CREATE TABLE IF NOT EXISTS conversations (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  session_id TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
  content TEXT NOT NULL,
  language TEXT NOT NULL DEFAULT 'en',
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conversations_session_id
  ON conversations(session_id);
CREATE INDEX IF NOT EXISTS idx_conversations_created_at
  ON conversations(created_at DESC);

-- Memories table
-- Stores persistent facts learned about the user (RAG)
CREATE TABLE IF NOT EXISTS memories (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  content TEXT NOT NULL,
  embedding vector(1536),
  category TEXT DEFAULT 'general',
  language TEXT NOT NULL DEFAULT 'en',
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memories_embedding
  ON memories USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 100);

-- Contacts table
-- Stores known contacts for WhatsApp, calls, etc.
CREATE TABLE IF NOT EXISTS contacts (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  name TEXT NOT NULL,
  phone TEXT,
  whatsapp TEXT,
  email TEXT,
  notes TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_contacts_name
  ON contacts(name);

-- Tools log table
-- Stores every tool execution for audit and learning
CREATE TABLE IF NOT EXISTS tools_log (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  session_id TEXT NOT NULL,
  tool_name TEXT NOT NULL,
  input JSONB,
  output JSONB,
  success BOOLEAN DEFAULT TRUE,
  duration_ms INTEGER,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tools_log_session_id
  ON tools_log(session_id);
CREATE INDEX IF NOT EXISTS idx_tools_log_tool_name
  ON tools_log(tool_name);

-- Device config table
-- Stores CyberBot configuration key-value pairs
CREATE TABLE IF NOT EXISTS device_config (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  description TEXT,
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Default config values
INSERT INTO device_config (key, value, description) VALUES
  ('default_language', 'en', 'Default response language'),
  ('voice_enabled', 'true', 'TTS enabled or disabled'),
  ('wake_word', 'hey cyberbot', 'Wake word to activate listening'),
  ('owner_name', 'Henrique', 'Primary user name'),
  ('timezone', 'Europe/Dublin', 'Device timezone'),
  ('personality', 'cyberpunk', 'CyberBot personality mode')
ON CONFLICT (key) DO NOTHING;

-- RPC function for vector similarity search
-- Used by RAG to find relevant memories
CREATE OR REPLACE FUNCTION match_memories(
  query_embedding vector(1536),
  match_threshold FLOAT DEFAULT 0.7,
  match_count INT DEFAULT 5
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
BEGIN
  RETURN QUERY
  SELECT
    memories.id,
    memories.content,
    memories.category,
    memories.language,
    1 - (memories.embedding <=> query_embedding) AS similarity
  FROM memories
  WHERE 1 - (memories.embedding <=> query_embedding) > match_threshold
  ORDER BY memories.embedding <=> query_embedding
  LIMIT match_count;
END;
$$;

-- RPC function to get recent conversation history
CREATE OR REPLACE FUNCTION get_recent_history(
  p_session_id TEXT,
  p_limit INT DEFAULT 20
)
RETURNS TABLE (
  role TEXT,
  content TEXT,
  language TEXT,
  created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
AS $$
BEGIN
  RETURN QUERY
  SELECT
    conversations.role,
    conversations.content,
    conversations.language,
    conversations.created_at
  FROM conversations
  WHERE conversations.session_id = p_session_id
  ORDER BY conversations.created_at DESC
  LIMIT p_limit;
END;
$$;
