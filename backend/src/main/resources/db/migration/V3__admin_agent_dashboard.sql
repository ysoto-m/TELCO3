CREATE TABLE IF NOT EXISTS agent_vicidial_credentials (
  id BIGSERIAL PRIMARY KEY,
  app_username VARCHAR(120) NOT NULL,
  agent_user VARCHAR(120) NOT NULL,
  agent_pass_encrypted TEXT NOT NULL,
  phone_login VARCHAR(120) NOT NULL,
  phone_pass_encrypted TEXT NOT NULL,
  campaign VARCHAR(120),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(app_username, agent_user)
);
