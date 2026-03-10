# Auditoria de carpeta `vicidial` (estado actual)

Fuente: inspeccion de codigo y referencias reales desde flujos activos backend/frontend.

Clasificacion usada:

- `activo`
- `compatibilidad/fallback`
- `deprecated (de facto)`
- `sin uso aparente`

## 1) Clases

| Clase | Estado | Nota |
|---|---|---|
| `VicidialService` | activo | Orquestacion AGC runtime para dial/hangup/logout/context |
| `VicidialClient` | activo | Cliente HTTP AGC y wrappers de acciones |
| `VicidialDialRequestBuilder` | activo | Construccion de payload de dial next/manual |
| `VicidialDialResponseParser` | activo | Parseo de respuesta `manDiaLnextCaLL` |
| `VicidialRealtimeQueryService` | activo | Queries runtime a BD Vicidial para realtime/disposiciones |
| `VicidialRealtimeStatusMapper` | activo | Traduccion de estado tecnico a visible |
| `VicidialRuntimeDataSourceFactory` | activo | Datasource dinamico Vicidial DB |
| `VicidialConfigService` | activo | Fuente de verdad de configuracion Vicidial |
| `VicidialSessionClient` | activo | Sesion AGC para connect phone |
| `VicidialCampaignParser` | activo | Parse HTML de campanas para login agente |
| `CampaignController` | activo | `GET /api/campaigns/{campaignId}` |
| `VicidialDiagnosticsService` | compatibilidad/fallback | Diagnostico y guardas de conectividad |
| `VicidialStartupValidator` | compatibilidad/fallback | Validacion startup en dev |
| `VicidialDevDiagController` | compatibilidad/fallback | Endpoint dev `/api/dev/vicidial/diag` |
| `DevEnvironmentCondition` | compatibilidad/fallback | Activa diagnostico solo en dev |
| `VicidialServiceException` | activo | Contrato de error de integracion |
| `VicidialDialProperties` | activo | Propiedades configurables de dial |

## 2) Metodos clave por estado

## `VicidialService`

`activo`:

- `resolveModeForCampaign`
- `campaignMode`
- `resolveManualDialListId`
- `followUpManualDial`
- `syncPostCallDisposition`
- `logoutAgentSession`
- `hangupActiveCall`
- `classifyActiveLead`
- `resolveRealtimeCallSnapshot` (ambas overloads)
- `resolveLeadFromRuntimeTables`

`deprecated (de facto)`:

- `dialNextWithLeadRetry`
- `resolveManualDialLead`
- `manualDialLogEnd`

## `VicidialClient`

`activo`:

- `manualDialNextCall(String, Map)`
- `updateSettings`, `callbacksCount`
- `confExtenCheck`, `vdcScriptDisplay`, `manualDialLookCall`
- `monitorConf`, `hangupConfDial`, `hangup`
- `manualDialLogCall`, `updateLead`, `updateDispo`, `runUrls`, `userLogout`
- `parseKeyValueLines`
- `activeLeadSafe`, `leadInfo`
- `campaignsForAgent`, `connectToCampaign`
- `clearAgentCookies`
- `previewAction`, `pause`

`compatibilidad/fallback`:

- `externalStatus`
- `agentLogout`
- `liveAgents`
- `campaigns`
- `addLead` (importador legacy)

`deprecated (de facto)`:

- `manualDialNextCall(Map)` (sin cookie/session por agente)
- `connectCampaign`
- `campaignDialMethod`, `campaignDialConfig`
- `activeLead`

`sin uso aparente`:

- `externalDialManualNext`
- `evaluateManualDialBody`
- `leadSearch`
- `agentLogin`
- `agentStatus`

## `VicidialRuntimeDataSourceFactory`

`activo`:

- `getOrCreate`

`sin uso aparente`:

- `invalidate`

## `VicidialDialResponseParser`

`activo`:

- `parseDetailed`

`deprecated (de facto)`:

- `parse` (wrapper no utilizado por flujos actuales)

## 3) Frontera CRM vs Vicidial

Evaluacion:

- frontera es mayormente correcta
- integracion externa vive en `vicidial`
- negocio CRM reutilizable vive en `campaign/core` y adaptadores de campana

Deuda pendiente (sin tocar en esta fase):

- reducir tamaño de `AgentController`
- seguir desacoplando endpoints admin legacy de integracion directa

