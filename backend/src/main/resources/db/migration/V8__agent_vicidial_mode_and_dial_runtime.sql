ALTER TABLE agent_vicidial_credentials
  ADD COLUMN IF NOT EXISTS connected_mode VARCHAR(20),
  ADD COLUMN IF NOT EXISTS current_dial_status VARCHAR(30),
  ADD COLUMN IF NOT EXISTS current_call_id VARCHAR(40),
  ADD COLUMN IF NOT EXISTS current_lead_id BIGINT;
