# Estado de implementación — Reto 2

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una etapa. Ver el detalle de cada etapa en
[`docs/PLAN-RETO2.md`](docs/PLAN-RETO2.md).

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Etapa | Descripción | Estado | Notas |
|---|---|---|---|
| 0 | Reorganización a monorepo (`services/`, `.gitignore`, `CLAUDE.md`, plan, este archivo) | ✅ | `empleados-service` incorporado con historial vía `git subtree` desde `reto1-microservicios`. |
| 1 | Scaffold `departamentos-service` (Go + chi + MySQL + golang-migrate) | ✅ | Endpoints `POST/GET /departamentos`, `GET /departamentos/{id}`, `GET /health` implementados. `go build`/`go vet`, build de la imagen y arranque real contra MySQL verificados; la validación Docker quedó cubierta al cerrar la Etapa 2. |
| 2 | `docker-compose.yml` raíz (redes, volúmenes, healthchecks, `depends_on: service_healthy`) | ✅ | Verificado end-to-end con `docker compose up --build` real (WSL2 + Docker Engine 28.4). Los 4 contenedores quedan `(healthy)`. Se encontraron y corrigieron 2 bugs: (1) el healthcheck provisional de `empleados-service` apuntaba a `GET /empleados`, que no existía; la Etapa 3 ya lo reemplazó por `GET /health`; (2) el healthcheck de `database-departamentos` usaba `mysqladmin ping -h localhost` (socket Unix), que daba un falso "healthy" contra el mysqld temporal de inicialización antes de que el puerto TCP estuviera listo. Se corrigió forzando `--protocol=tcp -h 127.0.0.1`. Repetido el arranque desde volumen limpio después del fix: sin errores. |
| 3 | Evolución `empleados-service`: Liquibase, `UNIQUE` en esquema, cliente HTTP a departamentos (timeout + retry con backoff), `/health` | ✅ | Liquibase crea `empleados` con rollback y restricciones `UNIQUE`; Hibernate quedó en `validate`. `POST /empleados` responde `201`, duplicados y departamento inexistente responden `400`. Cliente HTTP con timeout de 3s, 4 intentos y backoff 1s→2s→4s; agotamiento responde `503` sin persistir. `/health` verifica PostgreSQL y el healthcheck de Compose ya lo usa. Validado desde volúmenes limpios y con los 4 contenedores `(healthy)`. |
| 4 | OpenAPI/Swagger en ambos servicios | ✅ | `empleados-service`: Springdoc 3.1.1, UI en `/swagger-ui.html`, spec en `/v3/api-docs`. Documenta el contrato de la Etapa 3: `POST /empleados` (201/400/503), `GET /empleados/{id}` (200/404) y `GET /health` (200/503). `departamentos-service`: `openapi.yaml` estático embebido (sin codegen de swaggo), UI en `/swagger/index.html`. |
| 5 | Pruebas end-to-end, evidencia (`down` vs `down -v`, `docker compose ps`), README raíz con las 3 decisiones técnicas, colección Postman actualizada | 🔄 | Ya se verificaron los 4 contenedores `(healthy)`, persistencia con `down` vs `down -v`, validaciones de departamentos y el flujo completo departamento→empleado. Las tres validaciones de empleados responden `400` (email, `numeroEmpleado`, departamento inexistente), creación responde `201`, caída de departamentos responde `503` y no persiste. OpenAPI/Swagger (Etapa 4) ya está cerrado. **Falta**: actualizar/ampliar la colección Postman y consolidar la evidencia/documentación final. |

## Decisiones técnicas del enunciado (§Decisión técnica requerida del PDF)

Registrar aquí la versión corta con link al README que la justifica en detalle. Mientras no se
implemente la etapa correspondiente, queda como "propuesta" (ver `docs/PLAN-RETO2.md`).

| Decisión | Estado | Elección propuesta / tomada |
|---|---|---|
| Motor de BD por servicio | Tomada | PostgreSQL (empleados) + MySQL (departamentos) — persistencia poliglota |
| Creación de esquema | Tomada en ambos servicios | Liquibase (Java) / golang-migrate (Go), ambos con rollback |
| Garantía de unicidad | Tomada en ambos servicios | `UNIQUE`/`PRIMARY KEY` en esquema + consulta previa para dar mensaje 400 descriptivo |

## Cómo actualizar este archivo

1. Cambia el estado de la etapa que avanzaste (⬜ → 🔄 → ✅).
2. Si tomaste una decisión distinta a la propuesta en `docs/PLAN-RETO2.md`, anótalo en "Notas" y
   actualiza también la tabla de decisiones técnicas de arriba.
3. Si una etapa queda bloqueada por algo externo (credenciales, acceso, duda de negocio),
   anótalo igual en "Notas" en vez de dejarla en 🔄 silenciosamente.
