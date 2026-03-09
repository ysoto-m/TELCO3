CREATE TABLE IF NOT EXISTS contactos (
  id BIGSERIAL PRIMARY KEY,
  telefono VARCHAR(40) NOT NULL UNIQUE,
  nombres VARCHAR(180),
  apellidos VARCHAR(180),
  documento VARCHAR(80),
  origen VARCHAR(80),
  fecha_creacion TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  fecha_actualizacion TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS formulario_manual2 (
  id BIGSERIAL PRIMARY KEY,
  contacto_id BIGINT REFERENCES contactos(id),
  telefono VARCHAR(40) NOT NULL,
  comentario TEXT,
  campana VARCHAR(120) NOT NULL,
  creado_por VARCHAR(120) NOT NULL,
  fecha_registro TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS subtipificaciones (
  id BIGSERIAL PRIMARY KEY,
  campana VARCHAR(120) NOT NULL,
  codigo VARCHAR(80) NOT NULL,
  nombre VARCHAR(160) NOT NULL,
  activo BOOLEAN NOT NULL DEFAULT TRUE,
  fecha_creacion TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(campana, codigo)
);

CREATE TABLE IF NOT EXISTS gestiones_llamadas (
  id BIGSERIAL PRIMARY KEY,
  formulario_manual2_id BIGINT REFERENCES formulario_manual2(id),
  contacto_id BIGINT REFERENCES contactos(id),
  agente VARCHAR(120) NOT NULL,
  fecha_gestion TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  disposicion VARCHAR(60) NOT NULL,
  subtipificacion VARCHAR(80),
  observaciones TEXT,
  modo_llamada VARCHAR(30),
  lead_id BIGINT,
  call_id VARCHAR(120),
  unique_id VARCHAR(120),
  nombre_audio VARCHAR(255),
  duracion INTEGER,
  campana VARCHAR(120),
  telefono VARCHAR(40),
  vicidial_sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  vicidial_sync_error TEXT
);

CREATE INDEX IF NOT EXISTS ix_contactos_telefono ON contactos(telefono);
CREATE INDEX IF NOT EXISTS ix_contactos_documento ON contactos(documento);
CREATE INDEX IF NOT EXISTS ix_formulario_manual2_telefono ON formulario_manual2(telefono);
CREATE INDEX IF NOT EXISTS ix_formulario_manual2_contacto_id ON formulario_manual2(contacto_id);
CREATE INDEX IF NOT EXISTS ix_formulario_manual2_fecha ON formulario_manual2(fecha_registro);
CREATE INDEX IF NOT EXISTS ix_gestiones_llamadas_fecha ON gestiones_llamadas(fecha_gestion);
CREATE INDEX IF NOT EXISTS ix_gestiones_llamadas_agente ON gestiones_llamadas(agente);
CREATE INDEX IF NOT EXISTS ix_gestiones_llamadas_campana ON gestiones_llamadas(campana);
CREATE INDEX IF NOT EXISTS ix_gestiones_llamadas_disposicion ON gestiones_llamadas(disposicion);
CREATE INDEX IF NOT EXISTS ix_gestiones_llamadas_telefono ON gestiones_llamadas(telefono);
CREATE INDEX IF NOT EXISTS ix_gestiones_llamadas_contacto_id ON gestiones_llamadas(contacto_id);
