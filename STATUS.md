# Estado de implementación — Reto 2

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una etapa. Ver el detalle de cada etapa en
[`docs/PLAN-RETO2.md`](docs/PLAN-RETO2.md).

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Etapa | Descripción | Estado | Notas |
|---|---|---|---|
| 0 | Reorganización a monorepo (`services/`, `.gitignore`, `CLAUDE.md`, plan, este archivo) | ✅ | `empleados-service` incorporado con historial vía `git subtree` desde `reto1-microservicios`. |
| 1 | Scaffold `departamentos-service` (Go + chi + MySQL + golang-migrate) | ✅ | Endpoints `POST/GET /departamentos`, `GET /departamentos/{id}`, `GET /health` implementados. `go build`/`go vet` pasan localmente. **Pendiente de verificar**: build de la imagen Docker y arranque real contra MySQL. Probar con `docker compose up --build` y revisar logs de `departamentos-service`. |
| 2 | `docker-compose.yml` raíz (redes, volúmenes, healthchecks, `depends_on: service_healthy`) | ✅ | Verificado end-to-end con `docker compose up --build` real (WSL2 + Docker Engine 28.4). Los 4 contenedores quedan `(healthy)`. Se encontraron y corrigieron 2 bugs: (1) el healthcheck de `empleados-service` apuntaba a `GET /empleados`, que no existe todavía — se cambió a solo verificar que el servidor HTTP responde (sin exigir 200) hasta que la Etapa 3 agregue `/health`; (2) el healthcheck de `database-departamentos` usaba `mysqladmin ping -h localhost` (socket Unix), que da falso "healthy" contra el mysqld temporal de inicialización antes de que el puerto TCP esté listo — con volumen vacío esto hacía fallar `departamentos-service` al arrancar. Se corrigió forzando `--protocol=tcp -h 127.0.0.1`. Repetido el arranque desde volumen limpio después del fix: sin errores. |
| 3 | Evolución `empleados-service`: Liquibase, `UNIQUE` en esquema, cliente HTTP a departamentos (timeout + retry con backoff), `/health` | ⬜ | Depende de la Etapa 1 (ya resuelta: contrato de departamentos en `services/departamentos-service/README.md`). El compose raíz ya define `DEPARTAMENTOS_SERVICE_URL` para cuando se implemente el cliente. El healthcheck de `empleados-service` en el compose apunta provisionalmente a `GET /empleados`; cambiar a `/health` cuando esta etapa lo agregue. |
| 4 | OpenAPI/Swagger en ambos servicios | ⬜ | Puede avanzar en paralelo con 2 y 3. |
| 5 | Pruebas end-to-end, evidencia (`down` vs `down -v`, `docker compose ps`), README raíz con las 3 decisiones técnicas, colección Postman actualizada | 🔄 | Adelantado como parte de la verificación de la Etapa 2: `docker compose ps` con los 4 `(healthy)`, contraste `down` (datos sobreviven) vs `down -v` (se pierden) confirmado con `empleados-service` y `departamentos-service`, y probadas las validaciones de `departamentos-service` (id duplicado → 400, id inexistente → 404). **Falta**: las tres validaciones completas de `empleados-service` (unicidad ya responde, pero con `409` en vez del `400` que pide el PDF §4, y `POST /empleados` responde `200` en vez de `201`; la validación de departamento inexistente en `empleados-service` aún no existe — eso es la Etapa 3). Depende de que 3 y 4 terminen para cerrar del todo. |

## Decisiones técnicas del enunciado (§Decisión técnica requerida del PDF)

Registrar aquí la versión corta con link al README que la justifica en detalle. Mientras no se
implemente la etapa correspondiente, queda como "propuesta" (ver `docs/PLAN-RETO2.md`).

| Decisión | Estado | Elección propuesta / tomada |
|---|---|---|
| Motor de BD por servicio | Tomada | PostgreSQL (empleados) + MySQL (departamentos) — persistencia poliglota |
| Creación de esquema | Tomada en departamentos (golang-migrate) · pendiente en empleados (Etapa 3, Liquibase) | Liquibase (Java) / golang-migrate (Go), ambos con rollback |
| Garantía de unicidad | Tomada en departamentos (`PRIMARY KEY` + traducción del error 1062) · pendiente en empleados (Etapa 3) | `UNIQUE`/`PRIMARY KEY` en esquema + consulta previa para dar mensaje 400 descriptivo |

## Cómo actualizar este archivo

1. Cambia el estado de la etapa que avanzaste (⬜ → 🔄 → ✅).
2. Si tomaste una decisión distinta a la propuesta en `docs/PLAN-RETO2.md`, anótalo en "Notas" y
   actualiza también la tabla de decisiones técnicas de arriba.
3. Si una etapa queda bloqueada por algo externo (credenciales, acceso, duda de negocio),
   anótalo igual en "Notas" en vez de dejarla en 🔄 silenciosamente.
