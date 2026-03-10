# Frontera de arquitectura

## Mapa actual

### CRM core

- `com.telco3.agentui.campaign.core`
- `com.telco3.agentui.domain`

Responsabilidad:

- reglas comunes de contacto/interaccion/gestion
- validaciones de guardado final
- utilidades comunes de normalizacion/formato

### Campanas/adaptadores

- `com.telco3.agentui.manual2`
- `com.telco3.agentui.manual2.domain`
- `com.telco3.agentui.validacionclaroperu`
- `com.telco3.agentui.validacionclaroperu.domain`

Responsabilidad:

- formulario y validaciones especificas por campana
- delegacion al core comun para evitar duplicacion

### Integracion Vicidial/Asterisk

- `com.telco3.agentui.vicidial`
- `com.telco3.agentui.vicidial.domain`

Responsabilidad:

- cliente HTTP AGC
- parseo de respuestas AGC
- lifecycle de sesion AGC (connect/dial/hangup/logout)
- runtime SQL de Vicidial

### Compatibilidad legacy

- `com.telco3.agentui.legacy`

Responsabilidad:

- mantener endpoints/modelos de compatibilidad que siguen en uso
- no usar esta capa para desarrollo nuevo

## Regla para nuevas implementaciones

- nueva logica de negocio compartida: `campaign/core` y `domain`
- logica/persistencia especifica de campana: `manual2(.domain)` y `validacionclaroperu(.domain)`
- integracion externa con Vicidial: solo en `vicidial` y `vicidial.domain`
- compatibilidad: `legacy` (sin expandir)
