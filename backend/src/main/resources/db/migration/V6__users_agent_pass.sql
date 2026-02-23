ALTER TABLE users
  ADD COLUMN IF NOT EXISTS agent_pass_encrypted TEXT;
