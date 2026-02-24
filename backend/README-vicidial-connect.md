# Vicidial campaign connect (backend)

El flujo de conexión de campaña **no** usa `api.php(function=agent_login)` porque en ambientes reales de AGC ese flujo no replica el login de pantalla del agente.

Para conseguir el mismo comportamiento observado en el navegador, el backend hace `POST` a:

- `/agc/vicidial.php`
- `Content-Type: application/x-www-form-urlencoded`

## Parámetros enviados

Se envían estos campos exactamente (incluyendo campos vacíos):

- `DB=0`
- `JS_browser_height=641`
- `JS_browser_width=695`
- `phone_login=<anexo>`
- `phone_pass=anexo_<anexo>`
- `LOGINvarONE=`
- `LOGINvarTWO=`
- `LOGINvarTHREE=`
- `LOGINvarFOUR=`
- `LOGINvarFIVE=`
- `hide_relogin_fields=`
- `VD_login=<agentUser>`
- `VD_pass=<agentPass descifrado desde users.agent_pass_encrypted>`
- `VD_campaign=<campaignId>`

## Cookies / sesión

El cliente mantiene `CookieStore` por usuario (en memoria), con TTL de 30 minutos (configurable con `vicidial.session.cookie-ttl-minutes`).
Esto permite conservar sesión AGC entre requests del mismo agente y seguir redirects del login.

## Detección de éxito/error

- `ERROR: Invalid Username/Password` => `VICIDIAL_INVALID_CREDENTIALS`
- Cualquier `ERROR:` => `VICIDIAL_CAMPAIGN_CONNECT_FAILED`
- Señales de éxito HTML: `LOGOUT`, `Logout`, `AGENT_`, `vicidial.php?relogin`, `SESSION_name`
- Timeout/conectividad => `VICIDIAL_UNREACHABLE`
- HTTP 4xx/5xx => `VICIDIAL_HTTP_ERROR`
