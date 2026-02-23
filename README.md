# TELCO3 Agent UI para VICIdial (Backend + Frontend)

## Variables ENV necesarias
- `JWT_SECRET`
- `APP_CRYPTO_KEY` (AES, 16 bytes recomendados para cifrado de settings y credenciales de agente)
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASS`
- `VICIDIAL_BASE_URL` (se configura en `/api/admin/settings` o `/api/settings/vicidial`)
- `VICIDIAL_API_USER`, `VICIDIAL_API_PASS`, `VICIDIAL_SOURCE` (idem settings)
- `BACKEND_BASE_URL`, `FRONTEND_BASE_URL`

> Nota: este backend mantiene JWT + Spring Security y actúa como gateway entre Vicidial y Postgres local.

## Usuario inicial
- Usuario: `admin`
- Password inicial: `admin123` (seed en Flyway V2, cambiar tras primer inicio).

## Cómo correr
```bash
cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev
```

## Flujo Agent Login real (Vicidial)
### Endpoints
- `POST /api/agent/login-to-vicidial`
  - Body:
  ```json
  {
    "agentUser": "1001",
    "agentPass": "secret",
    "phoneLogin": "SIP/1001",
    "phonePass": "phoneSecret",
    "campaign": "CAMP001",
    "rememberCredentials": true
  }
  ```
- `POST /api/agent/logout-from-vicidial`
- `GET /api/agent/status?agentUser=1001`
- `GET /api/agent/campaigns?agentUser=1001`
  - Limitación: en algunos entornos Vicidial no devuelve campañas estrictamente por agente desde una sola función API; se entrega `raw` + mensaje de limitación.

### Curl de prueba
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'

curl -X POST http://localhost:8080/api/agent/login-to-vicidial \
  -H 'Authorization: Bearer <JWT>' \
  -H 'Content-Type: application/json' \
  -d '{"agentUser":"1001","agentPass":"secret","phoneLogin":"1001","phonePass":"phoneSecret","campaign":"CAMP001","rememberCredentials":true}'

curl 'http://localhost:8080/api/agent/status?agentUser=1001' -H 'Authorization: Bearer <JWT>'
curl -X POST http://localhost:8080/api/agent/logout-from-vicidial -H 'Authorization: Bearer <JWT>' -H 'Content-Type: application/json' -d '{"agentUser":"1001"}'
```

### Verificación de conexión real de agente en Vicidial
1. Consumir `GET /api/agent/status?agentUser=...` y validar estado no vacío.
2. En Admin Dashboard, revisar KPIs y tabla de agentes live (`/api/admin/agents`).
3. Revisar reporte/live_agents en Vicidial (o endpoint `/api/admin/summary` que consulta `live_agents`) para confirmar que el agente figura conectado.

## Admin Dashboard moderno (`/admin`, rol `REPORT_ADMIN`)
### Endpoints backend protegidos con `hasRole("REPORT_ADMIN")`
- `GET /api/admin/summary`: KPIs del día (agentes activos/incall/paused, interacciones del día), con modo degradado si Vicidial cae.
- `GET /api/admin/agents`: tabla de agentes live.
- `GET /api/admin/campaigns`: campañas (Vicidial o fallback local).
- `GET /api/admin/interactions`: paginado + filtros (`campaign`, `agentUser`, `dispo`, `from`, `to`, `page`, `size`).
- `GET /api/admin/interactions/export.csv`: export CSV con filtros.
- `GET/POST/PUT /api/admin/users`: CRUD básico de usuarios.
- `GET/PUT /api/admin/settings`: settings de Vicidial.

### Frontend
- Ruta `/admin` con:
  - Cards KPI.
  - Tabla de agentes con búsqueda.
  - Sección campañas.
  - Sección interactions con filtros + export.
  - Sección usuarios y settings.
- Polling cada 8s para `summary` y `agents`.
- Si Vicidial está caído, la UI muestra warning de degradación y sigue operando con datos locales.

## Notas de compatibilidad
- No se rompe Vicidial: las llamadas existentes (`external_status`, `preview_dial_action`, `external_pause`, etc.) se mantienen.
- Seguridad JWT/Spring Security permanece activa.
