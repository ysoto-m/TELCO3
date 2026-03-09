CREATE TABLE IF NOT EXISTS interacciones (
  id BIGSERIAL PRIMARY KEY,
  cliente_id BIGINT REFERENCES contactos(id),
  telefono VARCHAR(40) NOT NULL,
  campana VARCHAR(120),
  modo_llamada VARCHAR(30),
  agente VARCHAR(120),
  fecha_inicio TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT timezone('America/Lima', now()),
  fecha_fin TIMESTAMP WITHOUT TIME ZONE,
  duracion INTEGER,
  call_id VARCHAR(120),
  unique_id VARCHAR(120),
  lead_id BIGINT,
  nombre_audio VARCHAR(255),
  estado VARCHAR(30) NOT NULL DEFAULT 'ACTIVA'
);

CREATE INDEX IF NOT EXISTS ix_interacciones_telefono ON interacciones(telefono);
CREATE INDEX IF NOT EXISTS ix_interacciones_call_id ON interacciones(call_id);
CREATE INDEX IF NOT EXISTS ix_interacciones_lead_id ON interacciones(lead_id);
CREATE INDEX IF NOT EXISTS ix_interacciones_agente_estado ON interacciones(agente, estado);
CREATE INDEX IF NOT EXISTS ix_interacciones_fecha_inicio ON interacciones(fecha_inicio);

ALTER TABLE gestiones_llamadas
  ADD COLUMN IF NOT EXISTS interaccion_id BIGINT REFERENCES interacciones(id),
  ADD COLUMN IF NOT EXISTS tipificacion VARCHAR(60);

UPDATE gestiones_llamadas
SET tipificacion = COALESCE(NULLIF(tipificacion, ''), disposicion)
WHERE tipificacion IS NULL OR tipificacion = '';

ALTER TABLE subtipificaciones
  ADD COLUMN IF NOT EXISTS tipificacion VARCHAR(60);

UPDATE subtipificaciones
SET tipificacion = COALESCE(NULLIF(tipificacion, ''), 'GENERAL')
WHERE tipificacion IS NULL OR tipificacion = '';

ALTER TABLE contactos
  ALTER COLUMN fecha_creacion TYPE TIMESTAMP WITHOUT TIME ZONE USING (fecha_creacion AT TIME ZONE 'America/Lima'),
  ALTER COLUMN fecha_actualizacion TYPE TIMESTAMP WITHOUT TIME ZONE USING (fecha_actualizacion AT TIME ZONE 'America/Lima'),
  ALTER COLUMN fecha_creacion SET DEFAULT timezone('America/Lima', now()),
  ALTER COLUMN fecha_actualizacion SET DEFAULT timezone('America/Lima', now());

ALTER TABLE formulario_manual2
  ALTER COLUMN fecha_registro TYPE TIMESTAMP WITHOUT TIME ZONE USING (fecha_registro AT TIME ZONE 'America/Lima'),
  ALTER COLUMN fecha_registro SET DEFAULT timezone('America/Lima', now());

ALTER TABLE gestiones_llamadas
  ALTER COLUMN fecha_gestion TYPE TIMESTAMP WITHOUT TIME ZONE USING (fecha_gestion AT TIME ZONE 'America/Lima'),
  ALTER COLUMN fecha_gestion SET DEFAULT timezone('America/Lima', now());

ALTER TABLE subtipificaciones
  ALTER COLUMN fecha_creacion TYPE TIMESTAMP WITHOUT TIME ZONE USING (fecha_creacion AT TIME ZONE 'America/Lima'),
  ALTER COLUMN fecha_creacion SET DEFAULT timezone('America/Lima', now());
