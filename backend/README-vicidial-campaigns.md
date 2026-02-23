# Nota técnica: listado de campañas VICIdial para agente

## Endpoint y flujo implementado
Para `GET /api/agent/vicidial/campaigns` el backend usa **únicamente**:

- `POST /agc/vdc_db_query.php`
- `Content-Type: application/x-www-form-urlencoded`
- parámetros: `user=<agentUser>&pass=<agentPass>&ACTION=LogiNCamPaigns&format=html`

Este flujo replica el comportamiento validado desde navegador para la pantalla AGC al cargar campañas.

## Parámetros relevantes para `ACTION=LogiNCamPaigns`
Obligatorios en esta integración:
- `user`
- `pass`
- `ACTION=LogiNCamPaigns`
- `format=html` (se espera `<select><option ...>` para parseo)

Opcionales observados en instalaciones VICIdial según configuración:
- `SOURCE` o `source` para trazabilidad
- indicadores de depuración/DB en ciertos despliegues (no necesarios para este caso)

## Por qué antes fallaba
El flujo anterior mezclaba otro endpoint (`/agc/api.php`, `function=campaign_status`) y datos de `phone_login/phone_pass` en el listado inicial.
`LogiNCamPaigns` no usa ese esquema y espera autenticación directa del agente (`user/pass`) para devolver el `<select>` de campañas.

## Error `ERROR: Invalid Username/Password: |user|pass|...`
En la práctica indica que la validación de credenciales de agente en VICIdial falló para ese request.
Causas típicas:
- `user` o `pass` incorrectos
- usuario sin permisos vigentes o estado no válido
- restricciones de seguridad de la instalación (IP/ACL/políticas internas)

La API del backend lo mapea a `VICIDIAL_INVALID_CREDENTIALS`.

## Restricciones y consideraciones
- No se requieren cookies de navegador para este flujo.
- CORS no aplica entre backend y VICIdial (es llamada server-to-server).
- Puede haber políticas por IP en Apache/Nginx/firewall del servidor VICIdial.
- El conjunto de campañas depende de permisos del usuario (user/group/level y asignaciones de campaña).

## Recomendación para integraciones externas
Si se requiere una integración más formal para procesos no-AGC, evaluar `non_agent_api.php`/`api.php` según operación.
Para **listar campañas disponibles para login de agente**, se mantiene como implementación final `vdc_db_query.php` + `ACTION=LogiNCamPaigns` porque es el flujo ya validado funcionalmente en este proyecto.
