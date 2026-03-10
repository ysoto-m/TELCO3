# Contrato operativo Vicidial (orden y formato)

Este documento centraliza el orden operativo real que sigue el backend contra Vicidial AGC.

Objetivo:

- evitar implementaciones fuera de secuencia
- dejar explicitos parametros obligatorios
- mantener alineacion con el flujo del agente web oficial

## 1) Conectar anexo/campana

## 1.1 Connect phone

Endpoint CRM:

- `POST /api/agent/vicidial/phone/connect`

Llamada AGC principal:

- `POST /agc/vicidial.php`

Campos base usados:

- `phone_login`
- `phone_pass`
- `DB=0`
- `JS_browser_height`
- `JS_browser_width`
- `LOGINvarONE..FIVE` (vacios)
- `hide_relogin_fields`

## 1.2 Connect campaign

Endpoint CRM:

- `POST /api/agent/vicidial/campaign/connect`

Llamada AGC principal:

- `POST /agc/vicidial.php`

Campos operativos:

- `VD_login`
- `VD_pass`
- `VD_campaign`
- `phone_login`
- `phone_pass`
- browser metadata (`JS_browser_*`, `DB`, etc.)

## 2) Dial next/manual (flujo AGC minimo)

Endpoints CRM:

- `POST /api/agent/vicidial/dial/next`
- `POST /api/agent/vicidial/dial/manual`

Secuencia AGC esperada:

1. `POST /agc/vdc_db_query.php ACTION=CalLBacKCounT`
2. `POST /agc/vdc_db_query.php ACTION=update_settings`
3. `POST /agc/vdc_db_query.php ACTION=manDiaLnextCaLL`
4. `POST /agc/vdc_script_display.php`
5. `POST /agc/conf_exten_check.php` (poll de estado)
6. `POST /agc/vdc_db_query.php ACTION=manDiaLlookCaLL`
7. `POST /agc/manager_send.php ACTION=MonitorConf`
8. `POST /agc/vdc_db_query.php ACTION=manDiaLlogCaLL stage=start`

Confirmacion de llamada:

- no se considera confirmada solo por `manDiaLnextCaLL`.
- debe existir evidencia runtime (estado, callId/leadId, uniqueid/channel, consistencia de lookCall).

## 2.1 Payload base requerido para acciones runtime

Campos base (segun accion):

- `user`
- `pass`
- `server_ip`
- `session_name`
- `campaign`
- `phone_login`
- `conf_exten`
- `exten`
- `ext_context`
- `extension`
- `protocol`
- `agent_log_id`
- `format=text`

Campos de dial manual/next:

- `ACTION=manDiaLnextCaLL`
- `phone_number` (manual dial)
- `phone_code` (manual dial)
- `dial_timeout` (manual dial)
- `dial_prefix` (manual dial)
- `preview` (`NO`/`YES`)

## 3) Hangup/cierre de llamada

Endpoint CRM:

- `POST /api/agent/vicidial/call/hangup`

Secuencia AGC esperada:

1. `CalLBacKCounT`
2. `ACTION=HangupConfDial` (`/agc/manager_send.php`)
3. `ACTION=Hangup` (`/agc/manager_send.php`)
4. `ACTION=manDiaLlogCaLL stage=end`
5. `ACTION=updateLEAD`
6. `ACTION=updateDISPO`
7. `ACTION=RUNurls`
8. `ACTION=update_settings`

## 4) Logout agente

Endpoint CRM:

- `POST /api/agent/logout`

Secuencia AGC esperada:

1. `POST /agc/conf_exten_check.php` (pre-check)
2. `POST /agc/conf_exten_check.php` con `clicks=NormalLogout---LogouT---NORMAL`
3. `POST /agc/vdc_db_query.php ACTION=userLOGout`
4. `POST /agc/conf_exten_check.php` (validacion final)

Campos clave de `userLOGout`:

- `server_ip`
- `session_name`
- `user`
- `pass`
- `campaign`
- `conf_exten`
- `extension`
- `protocol`
- `agent_log_id`
- `phone_ip`
- `LogouTKicKAlL=1`
- `ext_context=default`
- `qm_extension`
- `stage=NORMAL`
- `dial_method`

## 5) Reglas de implementacion

- Mantener cookies/sesion AGC por agente para requests secuenciales.
- No marcar exito prematuro sin evidencia runtime de llamada.
- Mantener compatibilidad de aliases API de CRM, pero desarrollar contra endpoints oficiales.
- No mezclar este flujo con scraping HTML del frontend de Vicidial.

