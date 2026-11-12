# Arquitectura

Visión técnica del sistema de gestión del ciclo de vida del empleado. Los contratos compartidos
(envelope de eventos C1, health C2, error C3, métricas C4, logs C5 y plantilla de README C6) están
en [`proyecto-final/PLAN-PROYECTO-FINAL.md`](proyecto-final/PLAN-PROYECTO-FINAL.md).

## 1. Contexto

```mermaid
flowchart LR
  ADM([Administrador de RR. HH.]) -->|gestiona empleados, departamentos y vacaciones| SYS[[Sistema de ciclo de vida del empleado]]
  EMP([Empleado]) -->|activa su cuenta, inicia sesión, edita su perfil| SYS
  SYS -->|correos de activación, vacaciones y cuenta| MAIL[(Servidor SMTP<br/>Mailhog en desarrollo)]
  SYS -->|alertas| DIS[(Discord)]
  DEV([Equipo de desarrollo]) -->|push| GIT[(Repositorio Git)] -->|pipelines| SYS
```

## 2. Contenedores

```mermaid
flowchart TB
  subgraph frontend
    LB[balanceador · Traefik :8080]
  end
  subgraph backend
    GW[api-gateway · Node.js]
    EMP[empleados · Java]
    DEP[departamentos · Go]
    AUTH[auth · Python]
    PER[perfiles · Java]
    VAC[vacaciones · Node.js]
    NOT[notificaciones · Python]
  end
  subgraph messaging
    MQ[(RabbitMQ)]
    MH[Mailhog]
  end
  subgraph data
    DBE[(PostgreSQL empleados)]
    DBD[(MySQL departamentos)]
    DBA[(PostgreSQL auth)]
    DBP[(PostgreSQL perfiles)]
    DBV[(PostgreSQL vacaciones)]
    DBN[(PostgreSQL notificaciones)]
  end
  subgraph observability
    PROM[Prometheus] --> GRAF[Grafana]
    LOKI[Loki] --> GRAF
    PT[Promtail] --> LOKI
    ZIP[Zipkin]
  end
  subgraph cicd
    JEN[Jenkins] --> SON[SonarQube]
    JEN --> REG[Registry :5001]
  end
  LB --> GW
  GW -->|vía *-lb en el balanceador| EMP & DEP & AUTH & PER & VAC & NOT
  EMP -->|vía departamentos-lb| DEP
  EMP --- DBE
  DEP --- DBD
  AUTH --- DBA
  PER --- DBP
  VAC --- DBV
  NOT --- DBN
  EMP & AUTH & VAC --> MQ
  MQ --> AUTH & PER & VAC & NOT
  NOT --> MH
```

| Red | Miembros | Propósito |
|---|---|---|
| `frontend` | balanceador | Único punto de entrada de negocio (:8080) |
| `backend` | balanceador, Gateway y los seis servicios | HTTP interno; el balanceador publica los alias `<servicio>-lb` |
| `messaging` | RabbitMQ, Mailhog y los servicios que publican o consumen | Eventos y SMTP |
| `data` | cada base con su servicio | Ninguna base es alcanzable desde el borde ni desde CI |
| `observability` | Prometheus, Loki, Promtail, Zipkin, Grafana, los siete servicios y el balanceador | Métricas, logs y trazas |
| `cicd` | Jenkins, SonarQube, su base, Registry | CI/CD aislado de los datos de negocio |

## 3. Eventos

```mermaid
flowchart LR
  EMP[empleados] -->|empleado.creado<br/>empleado.actualizado<br/>empleado.retirado| X{{onboarding.eventos<br/>topic}}
  AUTH[auth] -->|usuario.creado · usuario.recuperacion<br/>cuenta.activada · cuenta.desactivada| X
  VAC[vacaciones] -->|vacaciones.programadas<br/>vacaciones.iniciadas · vacaciones.finalizadas| X
  X --> QA[q.auth] --> AUTH
  X --> QP[q.perfiles] --> PER[perfiles]
  X --> QV[q.vacaciones] --> VAC
  X --> QN[q.notificaciones] --> NOT[notificaciones]
  QA & QP & QV & QN -. reject / 3 reintentos .-> DLX{{onboarding.dlx}} --> DLQ[<q>.dlq]
```

Cada consumidor deduplica por el `id` del envelope en la misma transacción que su efecto, reintenta
tres veces con espera de 5 s (`<q>.retry`) y envía a su DLQ los mensajes inválidos o agotados. El
detalle por evento está en [`eventos.md`](eventos.md).

## 4. Secuencias de negocio

### Onboarding

```mermaid
sequenceDiagram
  actor A as Admin
  participant GW as Gateway
  participant E as Empleados
  participant D as Departamentos
  participant MQ as RabbitMQ
  participant AU as Auth
  participant P as Perfiles
  participant N as Notificaciones
  A->>GW: POST /empleados (JWT ADMIN)
  GW->>E: proxy
  E->>D: GET /departamentos/{id} (timeout, retry, CB)
  D-->>E: 200
  E->>E: guarda (commit)
  E-)MQ: empleado.creado (después del commit)
  E-->>A: 201
  MQ-)AU: empleado.creado → cuenta PENDIENTE_ACTIVACION
  AU-)MQ: usuario.creado (token de activación)
  MQ-)P: empleado.creado → perfil
  MQ-)N: usuario.creado → correo con enlace
  Note over A,N: El empleado activa la cuenta con POST /auth/reset-password y hace login
```

### Vacaciones

```mermaid
sequenceDiagram
  participant S as Scheduler (Vacaciones, con advisory lock)
  participant MQ as RabbitMQ
  participant AU as Auth
  participant N as Notificaciones
  S->>S: PROGRAMADA → EN_CURSO (commit)
  S-)MQ: vacaciones.iniciadas
  MQ-)AU: cuenta ACTIVA → SUSPENDIDA_TEMPORAL
  AU-)MQ: cuenta.desactivada (temporal)
  MQ-)N: correos de inicio y de cuenta
  S->>S: EN_CURSO → FINALIZADA (commit)
  S-)MQ: vacaciones.finalizadas
  MQ-)AU: SUSPENDIDA_TEMPORAL → ACTIVA
  AU-)MQ: cuenta.activada
```

### Offboarding

```mermaid
sequenceDiagram
  actor A as Admin
  participant E as Empleados
  participant MQ as RabbitMQ
  participant AU as Auth
  participant P as Perfiles
  participant N as Notificaciones
  A->>E: DELETE /empleados/{id} (vía Gateway)
  E->>E: estado RETIRADO + fechaRetiro (commit)
  E-)MQ: empleado.retirado
  MQ-)AU: cuenta DESACTIVADA_PERMANENTE (irreversible)
  MQ-)P: perfil archivado
  AU-)MQ: cuenta.desactivada (permanente)
  MQ-)N: correo de cuenta desactivada
```

## 5. Resiliencia y escalabilidad

- **Gateway → servicios:** circuito independiente por backend, timeout por servicio, reintento con
  backoff 1–2–4 s solo en `GET`, fallback 503 C3.
- **Empleados → Departamentos:** Resilience4j (timeout 5 s, backoff 1–2–4 s, circuit breaker); con
  el circuito abierto el empleado queda `PENDIENTE_VALIDACION` y se reconcilia después.
- **Balanceador (Traefik):** descubre las réplicas de su proyecto Compose, las verifica con health
  checks activos a `/health` cada 5 s, reintenta en otra réplica ante errores de conexión y, si un
  servicio se queda sin réplicas, responde 502 (el Gateway lo convierte en 503 y abre el circuito).
- **Escalado:** ningún servicio publica puerto ni usa `container_name`; `docker compose up --scale
  <servicio>=2` funciona en los siete. Los consumidores compiten por la misma cola y la deduplicación
  evita efectos dobles; el scheduler de Vacaciones toma un `pg_try_advisory_lock` por tick.

## 6. CI/CD y despliegue

```mermaid
flowchart LR
  P[push a proyecto-final] --> J[Jenkins · pollSCM]
  J --> B[Build · Lint · Unit · Integración]
  B --> S[SonarQube + Quality Gate ≥70 %]
  S --> SEC[Trivy deps · Gitleaks]
  SEC --> PKG[docker build<br/>tag commit-build + Trivy imagen]
  PKG --> R[(Registry :5001)]
  R --> E2E[Despliegue de prueba micro-ci + BDD]
  E2E --> D[Deploy al entorno<br/>smoke test · rollback automático]
```

- Los siete `Jenkinsfile` se generan desde [`../jenkins/plantillas/`](../jenkins/plantillas/).
- `docker-compose.deploy.yml` sustituye el `build` de un servicio por la imagen del Registry con el
  tag inmutable; `scripts/deploy.sh` despliega, verifica salud y revierte al tag anterior si falla.
- Cada imagen lleva las etiquetas `org.opencontainers.image.revision` (commit) y `version` (tag):
  commit → imagen → contenedor es trazable con `docker inspect`.

## 7. Observabilidad

| Señal | Implementación |
|---|---|
| Métricas | Cada servicio expone `/metrics` (o `/actuator/prometheus`) con HTTP, CPU/memoria y métricas de negocio; Prometheus descubre cada réplica por DNS |
| Logs | JSON con `timestamp, level, service, message, traceId, spanId`; Promtail los envía a Loki con labels `service` y `level` |
| Trazas | OpenTelemetry con propagación W3C en HTTP y AMQP; una traza de onboarding recorre los siete servicios en Zipkin |
| Alertas | Grafana: servicio caído, 5xx > 10 %, p95 > 2 s, mensajes en DLQ y memoria > 85 %; envío a Discord y correo |
