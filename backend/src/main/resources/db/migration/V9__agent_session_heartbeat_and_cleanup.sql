ALTER TABLE agent_vicidial_credentials
  ADD COLUMN IF NOT EXISTS crm_session_id VARCHAR(80),
  ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_browser_exit_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS session_status VARCHAR(40),
  ADD COLUMN IF NOT EXISTS cleanup_attempts INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS cleanup_status VARCHAR(80),
  ADD COLUMN IF NOT EXISTS logout_time TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_known_vicidial_status VARCHAR(40);

CREATE INDEX IF NOT EXISTS ix_agent_vicidial_credentials_connected_hb
  ON agent_vicidial_credentials(connected, last_heartbeat_at);

CREATE INDEX IF NOT EXISTS ix_agent_vicidial_credentials_crm_session_id
  ON agent_vicidial_credentials(crm_session_id);
