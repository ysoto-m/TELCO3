# Estado de superficie API (oficial / compat / deprecated / legacy)

Este archivo documenta el estado recomendado de endpoints backend.

## 1) Oficial

## Agent operacion

- `GET /api/agent/profile`
- `PUT /api/agent/profile/agent-pass`
- `POST /api/agent/vicidial/phone/connect`
- `POST /api/agent/vicidial/phone/disconnect`
- `GET /api/agent/vicidial/campaigns`
- `POST /api/agent/vicidial/campaign/connect`
- `GET /api/agent/vicidial/status`
- `GET /api/agent/active-lead`
- `GET /api/agent/context`
- `POST /api/agent/vicidial/dial/next`
- `POST /api/agent/vicidial/dial/manual`
- `POST /api/agent/vicidial/call/hangup`
- `POST /api/agent/logout`

## Agent formularios campana

- `GET /api/agent/manual2/disposiciones`
- `GET /api/agent/manual2/subtipificaciones`
- `GET /api/agent/manual2/contacto`
- `POST /api/agent/manual2/gestion`
- `GET /api/agent/validacion-claro-peru/disposiciones`
- `GET /api/agent/validacion-claro-peru/subtipificaciones`
- `GET /api/agent/validacion-claro-peru/formulario`
- `POST /api/agent/validacion-claro-peru/gestion`

## Admin settings/realtime

- `GET /api/admin/settings`
- `PUT /api/admin/settings`
- `GET /api/admin/vicidial/realtime/summary`
- `GET /api/admin/vicidial/realtime/agents`
- `GET /api/admin/vicidial/realtime/pause-codes`
- `GET /api/admin/vicidial/realtime/campaigns`

## 2) Compatibilidad (mantener por clientes actuales)

- `GET /api/settings/vicidial`
- `PUT /api/settings/vicidial`
- `POST /api/agent/vicidial/manual/next` (alias de `dial/next`)
- `GET /api/admin/summary` (AdminPage legacy)
- `GET /api/admin/agents` (AdminPage legacy)
- `GET /api/admin/campaigns` (AdminPage legacy)

## 3) Deprecated

(siguen funcionando, pero no deben usarse para nuevas integraciones)

- `POST /api/agent/vicidial/poll`
- `POST /api/agent/manual2/formulario`
- `GET /api/agent/manual2/historial`
- `GET /api/admin/manual2/historial`
- `GET /api/admin/config/vicidial`
- `PUT /api/admin/config/vicidial`
- `PUT /api/admin/users/{id}`
- `/api/reports/*`

## 4) Dominio legacy (modelo de datos)

No eliminar en esta fase. Mantener solo por compatibilidad.

- paquete Java: `com.telco3.agentui.legacy`
- `interactions`
- `customers`
- `customer_phones`
- endpoints asociados de reportes legacy

Camino recomendado para nuevas campanas:

- `contactos`
- `interacciones`
- `gestiones_llamadas`
- formularios por campana (`formulario_*`)
- `subtipificaciones`
