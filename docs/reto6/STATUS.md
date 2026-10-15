# Estado de implementación — Reto 6

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una fase. Ver el detalle de cada fase en
[`PLAN-RETO6.md`](PLAN-RETO6.md). Enunciado: [`reto6.pdf`](reto6.pdf).

El Reto 5 está cerrado; su estado histórico vive en [`docs/reto5/STATUS.md`](../reto5/STATUS.md).
Este reto no cambia contratos de negocio: si una prueba encuentra un bug, se corrige con su test
y se anota aquí.

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Fase | Descripción | Estado | Notas |
|---|---|---|---|
| 0 | Plan, este STATUS y `reports/` en `.gitignore` | ✅ | Plan escrito. `.gitignore` ignora `reports/` y `.coverage`. `CLAUDE.md` apunta al Reto 6. |
| 1 | `vacaciones-service`: tabla de solapamiento, las cuatro validaciones, caso de error con dobles, JUnit + lcov, umbral 70 % | ✅ | Criterio 1. `npm test`: 63 pruebas, cobertura de líneas 100 % sobre `validar.js`, `fechas.js`, `transiciones.js` y `servicio.js` (ramas 95,1 %). Umbral `--test-coverage-lines=70`. Refactor mínimo: `crearServicio` recibe `repo` (por defecto el real) para inyectar dobles. Regla rota (`<` → `>`) detectada por 17 pruebas, exit 1. Riesgo documentado: el solapamiento en producción lo decide el SQL de `buscarConflicto`, no `periodoQueBloquea`. |
| 2 | `empleados-service`: unitarias Mockito, `*IT` con Testcontainers + Liquibase, JaCoCo, Failsafe | ✅ | Criterio 1. `mvn test` (sin Docker): 74 unitarias. `mvn verify` con Docker: +5 integraciones contra `postgres:16` (`EmpleadoRepositoryIT`, `Reto1ApplicationIT`); sin Docker se omiten. Cobertura de líneas 88,1 % total, 85,9 % en `EmpleadoService`. `Reto1ApplicationTests` (H2) era integración disfrazada → `Reto1ApplicationIT`. H2 retirado del `pom.xml`. Regla rota (`existsByEmail`) detectada. Integración verificada en WSL con Maven offline y `TESTCONTAINERS_RYUK_DISABLED=true` (WSL sin DNS). |
| 3 | `auth-service`: `requirements-dev.txt`, reportes JUnit + cobertura, regla «retirado no se reactiva» | ✅ | Criterio 1 (refuerzo). `pytest`: 31 pruebas (`test_reglas.py` nuevo), 70 % total, `casos.py` 89 %, `seguridad.py` 97 %. Regla rota (`_vacaciones_finalizadas` reactiva todo) detectada por 2 pruebas. Reportes de notificaciones y Gateway no se agregaron (opcional). |
| 4 | `e2e-tests/` con Cucumber.js, World, polling, variables de entorno, humo | ✅ | Criterio 2. Cucumber.js + `fetch`, World nuevo por escenario, `.env.example` sin credenciales en código, `00-humo.feature` (`GET /health` → 200). `npm test` escribe `e2e-tests/reports/junit.xml`, `cucumber.json` y `cucumber.html`. Con `"type": "module"` el perfil es el default export de `cucumber.js`: envolverlo en otro `{ default }` hace que Cucumber ignore paths e imports. |
| 5 | Escenarios de seguridad y RBAC (4+) con Antecedentes | ✅ | Criterio 3. Seis escenarios en `01-seguridad.feature`: sin token, `Bearer abc` y firma alterada → 401 `No autenticado`; `USER` lista empleados (200) y no crea ni retira (403); `ADMIN` crea y consulta (201/200); `USER` edita su perfil (200) y el de otro (403). Antecedentes: `empleadoConCredenciales()` y un departamento de prueba. |
| 6 | Onboarding asíncrono, datos inválidos, calibración del polling | ✅ | Criterio 4. `02-onboarding.feature`: eventualmente `SEGURIDAD` con token, reset 200 `Contraseña actualizada`, login 200 `role=USER`. Antes de activar el login es 401 `Credenciales inválidas` (la cuenta nace sin hash; `La cuenta no está activa` aplica cuando la clave sí coincide). Esquema del alta, todos 400 según el servicio: departamento inexistente, campo obligatorio, email mal formado, email duplicado (`ConflictException` no es 409). Calibración 10 corridas el 14 oct 2026: min 259 ms, p50 286, p95 332, max 332, media 294. Se mantienen 15 × 2000 ms = 30 s (~90× el máximo). `empleadoConCredenciales()` queda listo para la Fase 7. Corrida verde: 12 escenarios, 84 pasos, `junit.xml` con 0 fallos. |
| 7 | Vacaciones (ciclo completo con `forzar-*`) y offboarding | ✅ | Criterio 5. `03-vacaciones.feature`: alta futura, fechas invertidas, solapamiento y ciclo `PROGRAMADA → EN_CURSO → FINALIZADA`, con bloqueo/reactivación y notificaciones observadas por polling. `04-offboarding.feature`: baja, aviso `CUENTA`, login 401, auditoría de `RETIRADO` y recuperación genérica cuyo reset se rechaza por estado. Fechas relativas UTC y `forzar-*`; no hay esperas fijas. |
| 8 | 3 corridas, fallo simulado, README con tabla de reportes, `EVIDENCIAS.md`, (opcional) `bdd-tests` en compose | ✅ | Criterio 6. Tres corridas consecutivas sobre los mismos volúmenes: 18/18 escenarios y 137/137 pasos (52,448 s; 49,415 s; 46,673 s). Fallo temporal 200→201: exit 1, mensaje preciso, `junit.xml` con 1 fallo y HTML en rojo; luego restaurado y validado verde. README raíz y `EVIDENCIAS.md` completos. RabbitMQ usa `restart: on-failure` para recuperarse de un fallo transitorio al inicializar un volumen nuevo. El servicio Compose `bdd-tests` no se añadió porque el enunciado lo marca opcional; `npm test` ya es el comando reproducible requerido. |

## Decisiones técnicas del enunciado

Versión corta. El «por qué» largo está en `PLAN-RETO6.md` y, al cerrar la fase, se copia al
README que el PDF pide.

| Decisión | Estado | Elección tomada |
|---|---|---|
| Servicios del Punto 1 | Tomada | Vacaciones (Node) y empleados (Java); auth (Python) como tercero. |
| ≥ 70 % | Tomada | `vacaciones-service`, medido sobre los cuatro archivos de dominio. |
| Runner Node | Tomada | `node --test` con reporters `junit` y `lcov`, no Jest. |
| Integración | Tomada | Testcontainers `postgres:16` en empleados. `*Test` = unitarias (Surefire), `*IT` = integración (Failsafe). |
| Framework BDD | Tomada | Cucumber.js + `fetch` nativo, Node 22. |
| World | Tomada | Una instancia por escenario: token, última respuesta, datos creados. |
| USER de prueba | Tomada | Sin semilla: se fabrica por escenario con onboarding real. Token de activación desde `GET /notificaciones/{id}`. |
| Aislamiento | Tomada | IDs únicos por escenario, sin limpieza. |
| Polling | Tomada y calibrada (Fase 6) | 15 × 2 s = 30 s, por entorno. Diez corridas: máximo 332 ms (media 294). El presupuesto es unas 90 veces ese máximo. Detalle en `e2e-tests/README.md`. |
| Tiempo de vacaciones | Tomada | Fechas de mañana + `forzar-inicio` / `forzar-fin`. No se espera al cron. |
| Reportes | Tomada | `<servicio>/reports/` en Node y Python, `target/` estándar en Maven, `e2e-tests/reports/` en BDD. |

## Cómo actualizar este archivo

1. Cambia el estado de la fase que avanzaste (⬜ → 🔄 → ✅).
2. Si tomaste una decisión distinta a la del plan, anótalo en «Notas» y actualiza la tabla de
   decisiones y la tabla de reportes del plan.
3. Una fase de pruebas no se marca ✅ solo porque los tests pasan: hay que haber visto el
   reporte en su ruta y, en las Fases 1 y 2, una regla rota detectada por un test.
4. Si un escenario BDD es intermitente, no se marca ✅ ni se sube el timeout a ciegas: se
   anota la causa aquí.
