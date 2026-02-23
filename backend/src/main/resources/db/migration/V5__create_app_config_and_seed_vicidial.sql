CREATE TABLE IF NOT EXISTS app_config (
  key VARCHAR(120) PRIMARY KEY,
  value TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO app_config(key, value, updated_at)
SELECT 'VICIDIAL_BASE_URL', base_url, NOW()
FROM vicidial_settings
WHERE id = 1
ON CONFLICT (key) DO NOTHING;

INSERT INTO app_config(key, value, updated_at)
SELECT 'VICIDIAL_API_USER', api_user, NOW()
FROM vicidial_settings
WHERE id = 1
ON CONFLICT (key) DO NOTHING;

INSERT INTO app_config(key, value, updated_at)
SELECT 'VICIDIAL_API_PASS', api_pass_encrypted, NOW()
FROM vicidial_settings
WHERE id = 1
ON CONFLICT (key) DO NOTHING;

INSERT INTO app_config(key, value, updated_at)
SELECT 'VICIDIAL_SOURCE', source, NOW()
FROM vicidial_settings
WHERE id = 1
ON CONFLICT (key) DO NOTHING;
