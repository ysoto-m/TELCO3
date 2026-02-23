ALTER TABLE agent_vicidial_credentials
  ADD COLUMN IF NOT EXISTS last_phone_login VARCHAR(120),
  ADD COLUMN IF NOT EXISTS last_campaign VARCHAR(120),
  ADD COLUMN IF NOT EXISTS remember_credentials BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS connected BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS connected_phone_login VARCHAR(120),
  ADD COLUMN IF NOT EXISTS connected_campaign VARCHAR(120),
  ADD COLUMN IF NOT EXISTS connected_at TIMESTAMPTZ;

ALTER TABLE agent_vicidial_credentials
  ALTER COLUMN agent_pass_encrypted DROP NOT NULL,
  ALTER COLUMN phone_login DROP NOT NULL,
  ALTER COLUMN phone_pass_encrypted DROP NOT NULL;

UPDATE agent_vicidial_credentials
SET
  last_phone_login = COALESCE(last_phone_login, phone_login),
  last_campaign = COALESCE(last_campaign, campaign)
WHERE last_phone_login IS NULL OR last_campaign IS NULL;

ALTER TABLE agent_vicidial_credentials
  DROP COLUMN IF EXISTS phone_login,
  DROP COLUMN IF EXISTS phone_pass_encrypted,
  DROP COLUMN IF EXISTS campaign;

CREATE UNIQUE INDEX IF NOT EXISTS ux_agent_vicidial_credentials_app_username
  ON agent_vicidial_credentials(app_username);
