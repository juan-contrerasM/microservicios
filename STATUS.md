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
| 3 | Evolución `empleados-service`: Liquibase, `UNIQUE` en esquema, cliente HTTP a departamentos (timeout + retry con backoff), `/health` | ✅ | Liquibase crea `empleados` con rollback y restricciones `UNIQUE`; Hibernate quedó en `validate`. `POST /empleados` responde `201`, duplicados y departamento inexistente responden `400`. Cliente HTTP con timeout de 3s, 4 intentos y backoff 1s→2s→4s; agotamiento responde `503` sin persistir. `/health` verifica PostgreSQL y el healthcheck de Compose ya lo usa. Validado desde volúmenes limpios y con los 4 contenedores `(healthy)`. **Corrección posterior**: al auditar contra el §4 del PDF se encontró que faltaba `GET /empleados` (listar todos), requerido en la tabla de endpoints del enunciado aunque no se repite en la rúbrica de evaluación. Se agregó `EmpleadoService.listarTodos()` + `EmpleadoController.listar()` (mismo patrón que `departamentos-service`), con pruebas unitarias nuevas en servicio y controller. Verificado en vivo (200, incluye los empleados creados) y en el spec de `/v3/api-docs`. |
| 4 | OpenAPI/Swagger en ambos servicios | ✅ | `empleados-service`: Springdoc 3.1.1, UI en `/swagger-ui.html`, spec en `/v3/api-docs`. Documenta el contrato de la Etapa 3: `POST /empleados` (201/400/503), `GET /empleados/{id}` (200/404) y `GET /health` (200/503). `departamentos-service`: `openapi.yaml` estático embebido (sin codegen de swaggo), UI en `/swagger/index.html`. |
| 5 | Pruebas end-to-end, evidencia (`down` vs `down -v`, `docker compose ps`), README raíz con las 3 decisiones técnicas, colección Postman actualizada | ✅ | Verificado end-to-end desde volumen limpio (`down -v && up --build`): los 4 contenedores quedan `(healthy)`, sin errores de conexión. Persistencia confirmada: `down` conserva los datos, `down -v` los borra. Flujo completo del §8 (departamento → empleado, 10 campos incluido `estado`) y las cuatro validaciones (id/email/numeroEmpleado duplicado, departamento inexistente) responden `400`. Escenario de resiliencia probado deteniendo `departamentos-service` de verdad: la petición tarda el backoff esperado y responde `503` sin persistir; al reiniciar el servicio, el mismo request responde `201`. Se reemplazó `Reto1.postman_collection.json` (obsoleto, esperaba `409`) por `Reto2.postman_collection.json` en la raíz, cubriendo ambos servicios (incluye `GET /empleados`); corrido con `newman`: 18 requests/36 assertions en las carpetas 0-2 y 3 assertions en la carpeta de resiliencia, 0 fallos. README raíz actualizado con la evidencia completa y las 3 decisiones técnicas. |

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
