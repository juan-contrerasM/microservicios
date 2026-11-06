# Manual de ejecución y sustentación — Reto 8

Guía paso a paso para levantar el stack, generar tráfico, simular el caos y mostrar la evidencia.
Resultados de referencia en [`EVIDENCIAS.md`](EVIDENCIAS.md).

## Antes de empezar

- **Todo se ejecuta desde la raíz del repositorio** (la carpeta que contiene `docker-compose.yml`):

  ```powershell
  cd "C:\Users\Jacobo Vargas\Documents\ALL\U\materias\micro"
  ```

- **Docker.** Con Docker Desktop, los comandos son `docker compose ...`. Si Docker solo está instalado
  dentro de WSL (sin Docker Desktop), anteponer `wsl`: `wsl docker compose ...`. Los scripts `.ps1`
  detectan esto solos.
- **Archivo de variables.** Los scripts usan `.env.example` por defecto. Para Compose se indica con
  `--env-file .env.example`.
- **Si PowerShell bloquea los scripts** («la ejecución de scripts está deshabilitada»), abrir la
  terminal y ejecutar una vez: `Set-ExecutionPolicy -Scope Process Bypass`.
- Conviene tener **dos terminales**: una para el tráfico (queda ocupada) y otra para los comandos.

## URLs

| Herramienta | URL | Acceso |
|---|---|---|
| Grafana | http://localhost:3000 | `admin` / `GrafanaAdmin123!` → Dashboards → Microservicios → «Reto 8 — Observabilidad de microservicios» |
| Prometheus | http://localhost:9091 | Status → Target health |
| Zipkin | http://localhost:9411 | — |
| Mailhog (alertas por email) | http://localhost:8025 | Buscar `grafana-alertas` |
| API Gateway | http://localhost:8080 | — |

## Paso 1 — Levantar el stack (≈ 2–3 min)

```powershell
docker compose --env-file .env.example up -d --build      # o: wsl docker compose ...
```

Solo hace falta `--build` la primera vez o si cambió código. Para no levantar Jenkins/SonarQube:

```powershell
docker compose --env-file .env.example up -d api-gateway empleados-service departamentos-service perfiles-service notificaciones-service vacaciones-service auth-service prometheus loki promtail zipkin grafana mailhog message-broker
```

## Paso 2 — Comprobar que todo está sano

```powershell
.\observability\verify.ps1
```

Debe terminar con: `Reto 8: stack saludable, datasources listos y siete targets Prometheus UP.`
Si falla porque algo aún arranca, esperar un minuto y repetir.

## Paso 3 — Generar tráfico (terminal 2)

```powershell
.\observability\trafico.ps1 -Segundos 900
```

Consulta los cinco backends a través del Gateway durante 15 minutos (incluye 404 y 401 a propósito
para el panel de errores) y al final imprime cuántas respuestas hubo por código. No crea ni borra
datos. Dejarlo corriendo mientras se muestra Grafana.

Qué esperar en el dashboard:

| Panel | Cuándo tiene datos |
|---|---|
| Estado de los servicios, CPU, memoria | De inmediato |
| Peticiones por segundo, errores 4xx/5xx | ≈ 1 minuto (ventana de 1 m) |
| Latencia p95, latencia media, ranking | 2–5 minutos (ventana de 5 m) |

## Paso 4 — Traza distribuida completa

El tráfico solo hace consultas; la traza de onboarding requiere **crear un empleado**. Lo más
rápido es la suite BDD (≈ 40 s, crea empleados reales):

```powershell
cd e2e-tests; npm test; cd ..
```

(La primera vez: `cd e2e-tests; copy .env.example .env; npm ci; cd ..`.)

En Zipkin: **Find a trace** → `serviceName = api-gateway`, `spanName = post` → abrir una traza
`POST /empleados`. Debe mostrar la cascada Gateway → empleados → departamentos → RabbitMQ → auth,
perfiles y notificaciones (7 servicios).

**Correlación con logs:** copiar el *Trace ID* y en Grafana → Explore → Loki ejecutar:

```logql
{service=~".+"} | json | traceId = "PEGAR_TRACE_ID"
```

Aparecen los logs de esa misma petición en varios servicios y lenguajes.

## Paso 5 — Caos 1: apagar un servicio

### Opción A — manual (recomendada en vivo)

```powershell
docker compose --env-file .env.example stop departamentos-service
```

| Tiempo aprox. | Qué mostrar |
|---|---|
| 15 s | Prometheus → Targets: `departamentos-service` en **DOWN** |
| 15–30 s | Grafana → panel «Estado de los servicios» en **rojo** |
| ≈ 1 min | Grafana → Alerting → Alert rules: «Servicio caído» en *Pending* y luego *Firing* |
| ≈ 1,5 min | Mailhog: email **[FIRING] Servicio caído departamentos-service** |
| 2–3 min | Con tráfico activo, también «Tasa de errores 5xx» del api-gateway |

Restaurar:

```powershell
docker compose --env-file .env.example start departamentos-service
```

En ≈ 1 min llegan los emails **[RESOLVED]**.

### Opción B — automática

```powershell
.\observability\caos.ps1 -Escenario caida
```

Dura ≈ 6 minutos. Genera su propio tráfico, detiene el servicio, espera la alerta y el email, lo
reinicia y espera el `RESOLVED`. Imprime cada hito con su hora. Aunque se interrumpa con Ctrl+C,
vuelve a iniciar el servicio.

## Paso 6 — Caos 2: latencia inducida

```powershell
.\observability\caos.ps1 -Escenario latencia -LatenciaMs 1500
```

Dura ≈ 5 minutos. Recrea `departamentos-service` con un retardo fijo de 1,5 s solo en
`/departamentos` (no afecta `/health` ni `/metrics`), genera tráfico, imprime el ranking de latencia
y lo restaura a `0`.

Qué mostrar mientras corre:

- Grafana → «Latencia p95» y «Ranking de latencia»: departamentos sube por encima de 1 s y arrastra al
  Gateway.
- Zipkin → traza `GET /departamentos` de ≈ 1,5 s: el span de departamentos ocupa casi todo.
- Mailhog: email **[FIRING] Latencia p95 superior a 1 s**.

## Paso 7 — Cierre

```powershell
.\observability\verify.ps1
```

Debe volver a estar todo en verde. Para apagar conservando datos:
`docker compose --env-file .env.example down`.

## Si algo sale mal

| Síntoma | Solución |
|---|---|
| `verify.ps1` dice targets DOWN justo después de levantar | Esperar 1–2 minutos (Java tarda en arrancar) y repetir |
| Departamentos quedó detenido tras interrumpir un script | `docker compose --env-file .env.example start departamentos-service` |
| Quedó la latencia activa | `docker compose --env-file .env.example up -d --no-deps departamentos-service` (vuelve a `0`) |
| Paneles de latencia vacíos | Falta tráfico o tiempo: correr `trafico.ps1` y esperar 5 minutos |
| Zipkin sin trazas | Guarda en memoria: se vacía al reiniciarse. Generar tráfico o correr la BDD de nuevo |
| En WSL, `docker compose up --build` no descarga imágenes («server misbehaving») | DNS de WSL: `wsl -u root resolvectl dns eth0 <gateway de WSL>` (temporal) |

## Respuesta a «¿qué servicio tardó más?»

Con la latencia inducida, `departamentos-service` (p95 ≈ 1,9 s). Se identifica en tres pasos:
1. **Grafana:** el ranking y el panel p95 lo ponen primero.
2. **Zipkin:** en una traza lenta, su span ocupa el 99 % del tiempo.
3. **Loki:** filtrando por ese `traceId`, se ven los `durationMs` del Gateway y de departamentos.

Sin caos, el más lento es `auth-service`, porque el login verifica la contraseña con un hash costoso a
propósito.
