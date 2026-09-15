# Estado de implementación — Reto 2

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una etapa. Ver el detalle de cada etapa en
[`docs/PLAN-RETO2.md`](docs/PLAN-RETO2.md).

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Etapa | Descripción | Estado | Quién | Notas |
|---|---|---|---|---|
| 0 | Reorganización a monorepo (`services/`, `.gitignore`, `CLAUDE.md`, plan, este archivo) | ✅ | Claude Code | `empleados-service` incorporado con historial vía `git subtree` desde `reto1-microservicios`. |
| 1 | Scaffold `departamentos-service` (Go + chi + MySQL + golang-migrate) | ⬜ | — | Endpoints `POST/GET /departamentos`, `GET /departamentos/{id}`, `GET /health`. |
| 2 | `docker-compose.yml` raíz (redes, volúmenes, healthchecks, `depends_on: service_healthy`) | ⬜ | — | Depende de la Etapa 1. |
| 3 | Evolución `empleados-service`: Liquibase, `UNIQUE` en esquema, cliente HTTP a departamentos (timeout + retry con backoff), `/health` | ⬜ | — | Depende de la Etapa 1 (contrato de departamentos). |
| 4 | OpenAPI/Swagger en ambos servicios | ⬜ | — | Puede avanzar en paralelo con 2 y 3. |
| 5 | Pruebas end-to-end, evidencia (`down` vs `down -v`, `docker compose ps`), README raíz con las 3 decisiones técnicas, colección Postman actualizada | ⬜ | — | Depende de 1–4. |

## Decisiones técnicas del enunciado (§Decisión técnica requerida del PDF)

Registrar aquí la versión corta con link al README que la justifica en detalle. Mientras no se
implemente la etapa correspondiente, queda como "propuesta" (ver `docs/PLAN-RETO2.md`).

| Decisión | Estado | Elección propuesta / tomada |
|---|---|---|
| Motor de BD por servicio | Propuesta (Etapa 1 la fija en código) | PostgreSQL (empleados) + MySQL (departamentos) — persistencia poliglota |
| Creación de esquema | Propuesta (Etapa 1 y 3 la implementan) | Liquibase (Java) / golang-migrate (Go), ambos con rollback |
| Garantía de unicidad | Propuesta (Etapa 3 la implementa) | `UNIQUE` en esquema + consulta previa para dar mensaje 400 descriptivo |

## Cómo actualizar este archivo

1. Cambia el estado de la etapa que avanzaste (⬜ → 🔄 → ✅).
2. Pon tu nombre o usuario en "Quién".
3. Si tomaste una decisión distinta a la propuesta en `docs/PLAN-RETO2.md`, anótalo en "Notas" y
   actualiza también la tabla de decisiones técnicas de arriba.
4. Si una etapa queda bloqueada por algo externo (credenciales, acceso, duda de negocio),
   anótalo igual en "Notas" en vez de dejarla en 🔄 silenciosamente.
