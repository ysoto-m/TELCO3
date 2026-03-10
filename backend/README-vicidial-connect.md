# Vicidial connect notes

Este archivo fue simplificado para evitar duplicacion documental.

La documentacion vigente de flujo y contrato AGC esta en:

- ../docs/vicidial-call-contract.md
- ../docs/vicidial-integration-audit.md
- ../docs/api-surface-status.md

Resumen:

- Conexion de anexo/campana usa /agc/vicidial.php
- Dial next/manual usa secuencia AGC completa con confirmacion runtime
- Hangup y logout siguen secuencias alineadas al flujo oficial de agente web
- Endpoints legacy se mantienen solo por compatibilidad
