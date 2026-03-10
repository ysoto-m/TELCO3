CREATE TABLE IF NOT EXISTS formulario_validacion_claro_peru (
  id BIGSERIAL PRIMARY KEY,
  cliente_id BIGINT REFERENCES contactos(id),
  nombres VARCHAR(180),
  apellidos VARCHAR(180),
  documento VARCHAR(80) NOT NULL,
  comentario TEXT,
  encuesta VARCHAR(2) NOT NULL,
  fecha_registro TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT timezone('America/Lima', now()),
  creado_por VARCHAR(120) NOT NULL,
  campana VARCHAR(120) NOT NULL
);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'ck_formulario_validacion_claro_peru_encuesta'
  ) THEN
    ALTER TABLE formulario_validacion_claro_peru
      ADD CONSTRAINT ck_formulario_validacion_claro_peru_encuesta
      CHECK (encuesta IN ('SI', 'NO'));
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_formulario_validacion_claro_peru_documento
  ON formulario_validacion_claro_peru(documento);

CREATE INDEX IF NOT EXISTS ix_formulario_validacion_claro_peru_fecha
  ON formulario_validacion_claro_peru(fecha_registro);

ALTER TABLE gestiones_llamadas
  ADD COLUMN IF NOT EXISTS formulario_validacion_claro_peru_id BIGINT REFERENCES formulario_validacion_claro_peru(id);

