-- Migration 002: Enable RLS on all tables
-- Backend uses service_role key which bypasses RLS
-- This blocks any direct client access with anon key

-- Enable RLS
ALTER TABLE conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE memories ENABLE ROW LEVEL SECURITY;
ALTER TABLE contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE tools_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_config ENABLE ROW LEVEL SECURITY;

-- Deny all access for anon role (service_role bypasses automatically)
CREATE POLICY "deny_anon_conversations" ON conversations
  FOR ALL TO anon USING (false);

CREATE POLICY "deny_anon_memories" ON memories
  FOR ALL TO anon USING (false);

CREATE POLICY "deny_anon_contacts" ON contacts
  FOR ALL TO anon USING (false);

CREATE POLICY "deny_anon_tools_log" ON tools_log
  FOR ALL TO anon USING (false);

CREATE POLICY "deny_anon_device_config" ON device_config
  FOR ALL TO anon USING (false);
