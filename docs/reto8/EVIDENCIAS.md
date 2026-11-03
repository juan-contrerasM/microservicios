# Evidencias — Reto 8

Registro de verificación de las fases 1 a 4 sobre el stack integrado.

## Verificación local

| Componente | Comando | Resultado |
|---|---|---|
| Compose | `docker compose --env-file .env.example config -q` | Correcto |
| API Gateway | `npm test` | 21/21 |
| Vacaciones | `npm test` | 64/64 |
| Auth | `pytest -q` | 31/31 |
| Notificaciones | `pytest -q` | 12/12 |
| Empleados | `mvnw test` | 74/74 |
| Perfiles | `mvnw test` | 4/4 |
| Departamentos | `go test ./...` | Correcto |
| Inicio OTel Node | importación previa de `telemetria.js` | Correcto; salida JSON válida en ambos servicios |
| Script de verificación | parser de PowerShell | Sintaxis correcta |

Fecha de ejecución: 2026-11-02, zona horaria America/Bogota.

## Evidencia integrada

- `observability/verify.ps1`: stack saludable, datasources Prometheus/Loki y siete targets `UP`.
- Onboarding real BDD: 1 escenario, 14 pasos, todos correctos.
- Traza onboarding `434a2652025a4a142b17fcd44b199cc4`: 66 spans, siete servicios, una sola raíz y cero spans huérfanos.
- Traza scheduler `b8d8cc298c80d4264a52fcfc89ff1a9e`: 30 spans y continuidad entre vacaciones, RabbitMQ, auth y notificaciones.
- Loki encontró logs por `service` y por ambos `traceId`; Grafana aprovisionó el enlace derivado hacia Zipkin.
- La revisión detectó un token de restablecimiento en un log de notificaciones. Se eliminó el cuerpo,
  destino y email de los logs y se agregó una prueba de regresión. La repetición final produjo cero
  coincidencias de token, JWT, contraseña o email en los logs correlacionados.

Repetición automática de infraestructura y correlación:

```powershell
powershell -ExecutionPolicy Bypass -File observability/verify.ps1
powershell -ExecutionPolicy Bypass -File observability/verify.ps1 -TraceId 434a2652025a4a142b17fcd44b199cc4
```
