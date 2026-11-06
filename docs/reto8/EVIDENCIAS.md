# Evidencias — Reto 8

Registro de verificación de las fases 1 a 7 sobre el stack integrado.

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

## Fases 5 a 7 — ejecución integrada

Fecha: 2026-11-03, hora de Bogotá (UTC−5). Stack levantado con
`docker compose --env-file .env.example up -d --build` (servicios de negocio + observabilidad, sin
Jenkins/SonarQube) sobre Docker 28.4 dentro de WSL2 (Ubuntu). Canal de alertas: email por SMTP a
Mailhog (ver la decisión en `STATUS.md`).

### Verificación previa

| Componente | Herramienta | Resultado |
|---|---|---|
| Recording rules | `rulefmt.ParseFile` de Prometheus v0.305.0 y carga real en Prometheus | 8 reglas, todas `ok` |
| PromQL de dashboard y alertas | `promql/parser.ParseExpr` (12 expresiones) | Correcto |
| Middleware de latencia (Go) | `go vet ./...`, `go test ./...` | Correcto; inactivo con `""`, `0`, negativo o inválido |
| Empleados / perfiles (histograma p95) | `mvnw test` con JDK 21 | 74/74 y 4/4 |
| Scripts PowerShell | parser de PowerShell 5.1 | Correcto |

### Fase 5 — dashboard y alertas

| Verificación | Resultado |
|---|---|
| `observability/verify.ps1` | 7 targets `UP`, datasources, dashboard `reto8-observabilidad`, 3 reglas, contact point `email-reto8`, 8 recording rules |
| Series normalizadas tras `trafico.ps1` (183 ciclos, 120 s) | Las 8 series (`up`, tráfico, 4xx, 5xx, p95, media, CPU, memoria) tienen datos de los **7** servicios |
| Grafana desde cero | `docker compose rm -sf grafana`, `docker volume rm microservicios_grafana_data`, `up -d grafana`: `verify.ps1` vuelve a pasar sin tocar la UI |

### Fase 6 — caída controlada (`caos.ps1 -Escenario caida`)

| Hora | Evento |
|---|---|
| 19:35:54 | `docker compose stop departamentos-service` (contenedor y volumen conservados) |
| 19:36:06 | Prometheus marca el target `DOWN` (12 s) |
| 19:37:16 | Email **[FIRING] Servicio caído departamentos-service** (82 s después de la caída) |
| 19:39:20 | Email **[FIRING] Tasa de errores 5xx superior al 10 % api-gateway**: proporción 5xx del 21 % |
| 19:40:08 | `docker compose start departamentos-service` |
| 19:40:25 | Target `UP` |
| 19:40:56 | Email **[RESOLVED] Servicio caído** |
| 19:41:00 | Email **[FIRING] Latencia p95 superior a 1 s api-gateway**: el p95 del Gateway llegó a 9,1 s durante la caída |
| 19:41:10 | Email **[RESOLVED] Tasa de errores 5xx** |
| 19:42:51 | Email **[RESOLVED] Latencia p95 api-gateway** |

Las tres reglas se dispararon con un fallo real y se resolvieron solas al restaurar el servicio.

### Fase 6 — latencia controlada (`caos.ps1 -Escenario latencia -LatenciaMs 1500`)

Retardo confirmado en el contenedor (`CHAOS_LATENCY_MS=1500`) de 19:43:38 a 19:47:44; restaurado a
`0` al terminar. Salida del script (ventana de 5 minutos):

| Servicio | Latencia media | p95 |
|---|---:|---:|
| departamentos-service | 0,587 s | **1,872 s** |
| api-gateway | 0,217 s | 1,622 s |
| auth-service | 0,306 s | 0,420 s |
| empleados-service | 0,016 s | 0,027 s |
| perfiles-service | 0,012 s | 0,025 s |
| notificaciones-service | 0,011 s | 0,023 s |
| vacaciones-service | 0,004 s | 0,009 s |

La media de departamentos queda por debajo de 1,5 s porque la ventana de 5 minutos todavía incluía
peticiones previas al retardo; el p95 sí refleja el caos. Emails: **[FIRING] Latencia p95
departamentos-service** a las 19:45:30 y **api-gateway** a las 19:47:00.

Traza `2e2dacd62aff20c6a7b8374618e7352b` (`GET /departamentos`): duración total 1,515 s, de la cual
el span `departamentos-service: get` ocupa **1,503 s (99 %)**. Loki devuelve, para ese mismo
`traceId`, el log del Gateway (Node, `durationMs` 1514) y el de departamentos (Go, `durationMs` 1502).

### Análisis: ¿qué servicio tardó más?

- **Con caos de latencia:** `departamentos-service`, con p95 de 1,87 s (panel «Latencia p95» y
  ranking). El span de Zipkin demuestra que el 99 % de la petición se pasó en ese servicio, y el p95
  del Gateway subió a 1,62 s: la latencia de un backend se propaga al borde.
- **Sin caos:** `auth-service` (media ≈ 0,31 s), porque su operación de negocio dominante es el login,
  que verifica la contraseña con un hash costoso a propósito. Es lentitud esperada, no un defecto; le
  siguen el Gateway y empleados, que hace más trabajo por petición (BD + llamada a departamentos +
  publicación en RabbitMQ).
- **Durante la caída:** el Gateway llegó a un p95 de 9,1 s y 21 % de respuestas 5xx. La caída de un
  servicio no solo produce errores: también alarga las peticiones que lo esperan en el borde.

### Trazabilidad completa y correlación

Traza de onboarding `7f88f6d6bef73ce982c7bc875291f2dd` (`POST /empleados` generado por la suite BDD):
**67 spans, 7 servicios, una sola raíz y cero spans huérfanos**. `verify.ps1 -TraceId` encontró 5 logs
correlacionados en Loki y ninguna coincidencia de JWT, contraseñas o tokens.

### Fase 7 — regresión

| Prueba | Resultado |
|---|---|
| BDD `e2e-tests` (`npm test`) | 18 escenarios / 137 pasos correctos en 3 de 4 corridas |
| Intermitencia observada | 1 corrida: «Consultar empleados sin token» falló en el paso de preparación `empleadoConCredenciales` con `reset-password` 400 («token no válido o expiró»). La prueba toma el primer aviso `SEGURIDAD` con token; si existe más de uno para el empleado, puede usar uno reemplazado. Es lógica de los Retos 5/6 y no se modificó en este reto |
| `verify.ps1` final | Stack saludable y siete targets `UP` tras ambos escenarios de caos |

### Defectos encontrados al ejecutar y corregidos

1. Grafana rechazó `interval: 15s` en las reglas (debe ser múltiplo de 10 s): pasó a `10s`.
2. Zipkin murió por `OutOfMemoryError` (heap por defecto de 128 MB, en memoria) tras ~40 minutos de
   uso: se acotó a 100 000 spans, heap de 512 MB y `restart: unless-stopped`.
3. Promtail enviaba a Loki logs de todos los contenedores del host, incluidos otros proyectos: ahora
   solo conserva los siete servicios del reto.
4. `caos.ps1`: el estado de alerta se leía de la primera serie (también existen series `Normal`) y la
   variable de retardo no cruzaba a WSL sin `WSLENV`; ahora el script además aborta si el contenedor no
   recibió el retardo.

### Capturas (`docs/reto8/capturas/`)

| Archivo | Contenido |
|---|---|
| `01-dashboard-caida.png` | Dashboard con `departamentos-service` en **DOWN** y los demás en UP |
| `02-alertas-estado.png` | Tres reglas *Provisioned* y las ocho recording rules |
| `03-mailhog-alertas.png` | Nueve emails FIRING/RESOLVED de las tres reglas |
| `04-zipkin-latencia-departamentos.png` | Traza lenta: 1,503 s de 1,515 s en departamentos |
| `05-loki-traceid.png` | LogQL por `traceId`: logs de Node y Go de la misma petición |
| `06-ranking-latencia.png` | Ranking del servicio más lento tras el caos |
| `07-latencia-p95-caos.png` | p95 de los dos experimentos con las anotaciones de alerta |
| `08-zipkin-onboarding.png` | Traza de onboarding: 7 servicios y 67 spans |

### Cómo repetirlo

```powershell
Copy-Item .env.example .env          # o pasar --env-file .env.example a docker compose
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File observability/verify.ps1
powershell -ExecutionPolicy Bypass -File observability/trafico.ps1 -Segundos 120
powershell -ExecutionPolicy Bypass -File observability/caos.ps1 -Escenario caida
powershell -ExecutionPolicy Bypass -File observability/caos.ps1 -Escenario latencia -LatenciaMs 1500
cd e2e-tests; npm test; cd ..
powershell -ExecutionPolicy Bypass -File observability/verify.ps1 -TraceId <traceId de un POST /empleados>
```

La bandeja de alertas está en `http://localhost:8025` (buscar `grafana-alertas`).
