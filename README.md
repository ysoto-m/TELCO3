# TELCO3 Agent UI para VICIdial (Backend + Frontend)

## Variables a completar
- `VICIDIAL_BASE_URL`, `VICIDIAL_API_USER`, `VICIDIAL_API_PASS`, `VICIDIAL_SOURCE`
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASS`
- `BACKEND_BASE_URL`, `FRONTEND_BASE_URL`

## Usuario inicial
- Usuario: `admin`
- Password inicial: `admin123` (seed en Flyway V2, cambiar tras primer inicio).

## SPRINT 0 — Contrato y scaffold
- Contrato OpenAPI: `openapi/openapi.yaml`.
- Estructura: `/backend`, `/frontend`, `/docker`.

**Cómo correr**
```bash
cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev
```

**Cómo validar**
```bash
curl -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'
```

## SPRINT 1 — Backend base
- Spring Boot 3 + Java 21, JWT, roles AGENT/REPORT_ADMIN.
- PostgreSQL + Hikari explícito + Flyway (`V1__init.sql`, `V2__seed_admin.sql`).
- Settings GET/PUT (`apiPass` no se retorna).
- Swagger: `/swagger-ui/index.html`.

**Cómo correr**
```bash
cd docker && docker compose up --build
```

**Cómo validar**
```bash
curl http://localhost:8080/actuator/health
```

## SPRINT 2 — VICIdial + Context/Tipificación
- Cliente `WebClient` encapsulado en backend.
- `/api/agent/active-lead`, `/api/agent/context`, `/api/agent/interactions`, retry.
- `syncStatus`: `PENDING|SYNCED|FAILED`.

## SPRINT 3 — Preview/Manual
- `/api/agent/preview-action`
- `/api/agent/pause`

## SPRINT 4 — Import CSV
- `/api/vicidial/leads/import`
- CSV mínimo: `dni,first_name,last_name,phone_number,list_id`
- Upsert customers + phones y `add_lead` por fila.

## SPRINT 5 — Frontend completo basado en OpenAPI
- React + MUI + Router + TanStack Query + RHF+Zod.
- Cliente frontend (`frontend/src/api/sdk.ts`) usando únicamente contrato definido.
- Pantallas: Login, Agent Atención (predictivo/preview), Admin settings/import/reports.
- Generación tipos OpenAPI:
```bash
cd frontend && npm run generate:api
```

## SPRINT 6 — Hardening
- Respuesta de error consistente (`ApiExceptionHandler`).
- Recomendaciones de seguridad:
  - Exponer backend solamente en red privada.
  - Allowlist IP de backend hacia VICIdial.
  - Rotar `JWT_SECRET` y `APP_CRYPTO_KEY`.
  - Cambiar password seed admin.

## TODOs (anti-invención)
- Parseo robusto de respuestas VICIdial depende formato exacto en ambiente real.
- Ajustar `dispoOptions` desde configuración real (hoy lista mínima fija).
- Añadir tests de integración contra sandbox VICIdial real.
