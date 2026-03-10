# APU CONTACT

CRM para contact center (Spring Boot + React) integrado con Vicidial/Asterisk.

## Arquitectura oficial

El sistema se organiza en 3 bloques:

1. CRM core
- contactos, interacciones, gestiones, subtipificaciones, reportes y utilidades comunes.

2. Adaptadores por campana
- Manual2
- ValidacionClaroPeru
- futuras campanas reutilizan el core comun y solo agregan reglas/formulario especifico.

3. Integracion Vicidial
- cliente AGC, parseo de respuestas, sesion AGC, dial/hangup/logout y consultas runtime Vicidial.

## Estructura backend (simple y mantenible)

- `backend/src/main/java/com/telco3/agentui/campaign/core`: core reutilizable CRM.
- `backend/src/main/java/com/telco3/agentui/domain`: CRM core oficial (contacto/interaccion/gestion/subtipificacion + usuarios/config base).
- `backend/src/main/java/com/telco3/agentui/manual2`: adaptador de campana Manual2.
- `backend/src/main/java/com/telco3/agentui/manual2/domain`: persistencia de formulario Manual2.
- `backend/src/main/java/com/telco3/agentui/validacionclaroperu`: adaptador de campana ValidacionClaroPeru.
- `backend/src/main/java/com/telco3/agentui/validacionclaroperu/domain`: persistencia de formulario ValidacionClaroPeru.
- `backend/src/main/java/com/telco3/agentui/vicidial`: integracion Vicidial/Asterisk.
- `backend/src/main/java/com/telco3/agentui/vicidial/domain`: persistencia de sesion/credenciales runtime Vicidial.
- `backend/src/main/java/com/telco3/agentui/config`: configuracion de seguridad y app.
- `backend/src/main/java/com/telco3/agentui/common`: manejo comun de errores/utilidades transversales.
- `backend/src/main/java/com/telco3/agentui/legacy`: compatibilidad legacy completa (modelo + endpoints legacy).

## Modelo oficial del CRM

Para desarrollo nuevo, el modelo oficial es:

- `ContactoEntity` / `ContactoRepository`
- `InteraccionEntity` / `InteraccionRepository`
- `GestionLlamadaEntity` / `GestionLlamadaRepository`
- `SubtipificacionEntity` / `SubtipificacionRepository`
- `UserEntity` / `UserRepository`
- `AppConfigEntity` / `AppConfigRepository`

Formularios por campana (fuera del core):

- `manual2.domain.FormularioManual2Entity` / `FormularioManual2Repository`
- `validacionclaroperu.domain.FormularioValidacionClaroPeruEntity` / `FormularioValidacionClaroPeruRepository`

Modelo de soporte Vicidial:

- `vicidial.domain.AgentVicidialCredentialEntity` / `AgentVicidialCredentialRepository`

Modelo legacy de compatibilidad:

- `com.telco3.agentui.legacy` (`Customer*`, `Interaction*`, `SyncStatus`, reportes y controllers legacy)

## Campanas/formularios activos

- Manual2
- ValidacionClaroPeru

## Endpoints oficiales y compatibilidad

Referencias:

- `docs/api-surface-status.md`

## Integracion Vicidial

Orden operativo y formato esperado:

- `docs/vicidial-call-contract.md`

Auditoria tecnica del paquete Vicidial:

- `docs/vicidial-integration-audit.md`

## Flujo minimo para llamar

Para poder marcar desde CRM el agente debe completar este orden:

1. `POST /api/agent/vicidial/phone/connect`
2. `POST /api/agent/vicidial/campaign/connect`
3. `POST /api/agent/vicidial/dial/manual` o `POST /api/agent/vicidial/dial/next`

Reglas operativas importantes:

- `phone/connect` solo conecta el anexo. No habilita marcacion por si solo.
- `campaign/connect` debe ejecutarse antes del dial porque ahi se obtiene y persiste la sesion runtime de Vicidial (`session_name`, `server_ip`, `conf_exten`, `agent_log_id`).
- `dial/manual` ejecuta preflight AGC y luego `manDiaLnextCaLL`. El backend no debe bloquear la marcacion manual solo porque runtime reporte `PAUSED` antes del request.
- La llamada solo se considera confirmada cuando Vicidial devuelve evidencia runtime de `INCALL` (`callId`, `leadId`, `uniqueId`, `channel`, `agentStatus`).
- Despues de un manual dial exitoso puede existir una ventana corta donde `GET /api/agent/context` o el primer `GET /api/agent/active-lead` todavia reflejen `PAUSED`. El estado correcto termina de converger en el siguiente poll de runtime.

Secuencia minima real de manual dial:

1. `update_settings`
2. `CalLBacKCounT`
3. `manDiaLnextCaLL`
4. follow-up runtime (`conf_exten_check`, `lookCall`, `MonitorConf`, `logCall`)

Si este orden no se respeta, Vicidial puede responder HTTP 200 al request pero no originar la llamada en Asterisk.

## Ejecucion local

Backend:

```bash
cd backend
mvn spring-boot:run
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

PostgreSQL (si usas Docker local):

```bash
cd docker
docker compose up -d postgres
```

## Configuracion

Archivo principal backend:

- `backend/src/main/resources/application.properties`

Variables principales:

- `SPRING_DATASOURCE_*` o `POSTGRES_*`
- `JWT_SECRET`
- `APP_CRYPTO_KEY`
- `VICIDIAL_BASE_URL`, `VICIDIAL_API_USER`, `VICIDIAL_API_PASS`, `VICIDIAL_SOURCE`

Frontend:

- `VITE_BACKEND_BASE_URL` (preferida)
- `VITE_API_BASE_URL` (compatibilidad)
