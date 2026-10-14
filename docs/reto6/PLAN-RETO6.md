# Plan de implementación — Reto 6: Automatización de pruebas

Basado en [`docs/reto6/reto6.pdf`](reto6.pdf). Este documento traza el plan en fases para que el
equipo pueda repartirse el trabajo sin pisarse. El avance real (no lo planeado) se registra en
[`STATUS.md`](STATUS.md). El Reto 5 queda cerrado; no reabrir etapas de
[`docs/reto5/PLAN-RETO5.md`](../reto5/PLAN-RETO5.md). Este reto **no cambia contratos de
negocio**: si una prueba descubre un bug, se corrige en el servicio con su propio test, pero no
se inventan rutas, códigos ni eventos nuevos para facilitar la prueba.

El contrato que las pruebas verifican es el del Reto 5: la tabla «Contrato de respuestas que no
debe romperse» y el RBAC de [`PLAN-RETO5.md`](../reto5/PLAN-RETO5.md). Si un escenario espera
algo distinto a eso, el escenario está mal, no el servicio.

## Qué hay que resolver (y qué no)

El PDF dice «el ecosistema no tiene una sola prueba automatizada». En este repo **no es
cierto**: ya hay pruebas sueltas que se escribieron durante los Retos 1 a 5. Lo que falta es
que sean una estrategia:

| Servicio | Lo que ya existe | Lo que falta para el Reto 6 |
|---|---|---|
| `empleados-service` (Java, Maven) | `EmpleadoServiceTest`, `EmpleadoValidationTest`, controller, cliente, publisher, `GlobalExceptionHandlerTest`. `h2` en scope test | JaCoCo, separar unitarias de integración, integración contra **PostgreSQL real** (no H2) |
| `vacaciones-service` (Node) | `node --test`: `validar.test.js`, `transiciones.test.js`, `openapi.test.js` | Tabla completa de solapamiento, `diasHabiles`, reportes JUnit + lcov, umbral 70 % |
| `auth-service` (Python) | pytest: `test_login.py`, `test_ciclo.py` | `pytest`, `pytest-cov` en dependencias de desarrollo, reportes |
| `notificaciones-service` (Python) | pytest: `test_procesar.py`, `test_openapi.py` | Fuera del mínimo; se le agregan reportes si sobra tiempo |
| `api-gateway` (Node) | `node --test` + supertest: `app.test.js` | Fuera del mínimo; reportes si sobra tiempo |
| `perfiles-service` (Java) | `PerfilEventoServiceTest` | Fuera del mínimo |
| `departamentos-service` (Go) | Nada | Fuera del mínimo |
| `e2e-tests/` | No existe | Todo: Puntos 2 a 6 |

Ningún Dockerfile corre pruebas (`-DskipTests`, `npm ci --omit=dev`) y así se queda: la imagen
es el artefacto de producción. Las pruebas se corren por comando, y en el Reto 7 desde Jenkins.

1. **La base de la pirámide se apoya en lo que ya existe.** Primero se audita cada test
   existente: ¿es unitario de verdad (sin Docker, sin red) o es integración disfrazada? Luego
   se completan los huecos que el PDF nombra, no se reescribe todo.
2. **La punta es un proyecto aparte.** `e2e-tests/` en la raíz, con su propio `package.json`.
   No es un microservicio: no va bajo `services/`, no tiene Dockerfile obligatorio ni base de
   datos. Habla con el sistema **solo** por `BASE_URL` (el Gateway, `:8080`).
3. **Todo produce archivos.** Cada suite escribe JUnit XML y cobertura (o Cucumber JSON/HTML en
   la BDD) en rutas fijas, y sale con código distinto de cero si algo falla. Esa tabla es el
   contrato con el Reto 7.

## Decisiones técnicas ya tomadas

Si alguien quiere cambiarlas, que lo discuta con el equipo antes de tocar el `package.json` de
`e2e-tests/` o el `pom.xml` de empleados.

| Decisión | Elección | Por qué |
|---|---|---|
| Servicios del Punto 1 | **`vacaciones-service` (Node)** y **`empleados-service` (Java)** como mínimo. `auth-service` (Python) como tercero | Son los que el PDF nombra en «lo que debe hacer» (las cuatro validaciones de vacaciones y la unicidad/retiro de empleados), y están en lenguajes distintos. Auth es el tercero porque su regla «retirado no se reactiva» es la más delicada del Reto 5 y ya tiene la mitad de los tests. |
| Servicio con cobertura ≥ 70 % | **`vacaciones-service`**, medido sobre `src/validar.js`, `src/fechas.js`, `src/transiciones.js` y `src/servicio.js` | Su lógica ya está en funciones puras (`evaluarAlta`, `seSolapan`, `periodoQueBloquea`, `diasHabiles`). Es donde el 70 % sale de pruebas que verifican reglas, no de tocar getters. Empleados se mide también, sin umbral obligatorio. |
| Runner de Node | **`node --test`** (el que ya usan vacaciones y el Gateway), no Jest | Node 22 trae reporter `junit`, reporter `lcov`, cobertura y umbral (`--test-coverage-lines`). Cambiar a Jest sería reescribir tests que funcionan para obtener lo mismo. `lcov.info` es lo que SonarQube lee para JS en el Reto 7. |
| Integración de empleados | **Testcontainers** con `postgres:16` + Liquibase real | El PDF señala que H2 «puede pasar la prueba y fallar en producción». Lo que se quiere detectar (restricción `UNIQUE` en el changelog, filtro por `estado`, mapeo de `fechaRetiro`) depende del motor. Requiere Docker en la máquina que corre `mvn verify`; Jenkins (Reto 7) lo tendrá porque también levanta el compose. H2 sale del `pom.xml` si ningún test lo sigue necesitando. |
| Unitarias vs integración en Maven | `*Test.java` = unitarias (Surefire, `mvn test`). `*IT.java` = integración (Failsafe, `mvn verify`) | Así «ninguna unitaria requiere Docker» es verificable: `mvn test` corre sin Docker. `mvn verify` corre las dos y JaCoCo. |
| Integración de vacaciones | Opcional, misma idea: `buscarConflicto` contra Postgres real | El solapamiento se decide en **SQL** (`fecha_inicio <= $fin AND fecha_fin >= $inicio`) y la función pura `periodoQueBloquea` lo replica. La unitaria prueba la regla; solo una integración prueba que el SQL dice lo mismo. Si hay tiempo, entra; si no, se documenta como riesgo. |
| Python | `pytest` + `pytest-cov` en `requirements-dev.txt` (no en `requirements.txt`) | La imagen no debe cargar herramientas de prueba. |
| Framework BDD | **Cucumber.js** (`@cucumber/cucumber`) en Node 22, con `fetch` nativo | Es Cucumber «de verdad»: soporta `# language: es`, y genera los **tres** reportes (HTML, JSON, JUnit) con formateadores propios. Behave no produce HTML ni un JSON 100 % compatible con Cucumber. Cucumber-JVM obligaría a otro proyecto Maven. Node es el lenguaje del Gateway y de vacaciones, el equipo ya lo conoce. `fetch` evita axios: una dependencia menos y no lanza excepción en 4xx, que aquí son resultados esperados. |
| Contexto compartido | **World de Cucumber, una instancia nueva por escenario** | Cucumber.js crea el World por escenario: nada se filtra entre escenarios. Guarda `baseUrl`, `token` actual, `ultimaRespuesta` (status + cuerpo), y los datos creados (`empleado`, `departamento`, `vacacion`, contraseña). Los pasos «Dado que estoy autenticado como …» hacen login y guardan el token; los «Cuando …» lo leen del World. |
| Autenticación en los Antecedentes | ADMIN: login real de la semilla (`ADMIN_USUARIO` / `ADMIN_PASSWORD`). USER: **no hay semilla**; se fabrica por escenario con onboarding real | El Reto 5 no siembra usuarios `USER`, y sembrarlos por SQL rompería la regla de no tocar la base de otro servicio. Un helper `empleadoConCredenciales()` crea departamento + empleado, espera el token, hace `reset-password` y login. Lo reutilizan los Puntos 3, 4 y 5. |
| Token de activación en la prueba | Se lee de `GET /notificaciones/{empleadoId}` (como ADMIN) y se extrae `token=` del mensaje `SEGURIDAD` | Es la misma vía que usa la colección Newman del Reto 5 y pasa por `BASE_URL`. MailHog sería un segundo puerto y una dependencia más. |
| Aislamiento de datos | **IDs únicos por escenario**, sin limpieza | Cada escenario genera un sufijo (`Date.now()` + aleatorio): `empleadoId`, `numeroEmpleado`, `email`, `departamentoId`. No se borra nada: borrar es `DELETE /empleados` = retiro con eventos, que ensucia más de lo que limpia. Correr la suite N veces sobre el mismo volumen debe pasar igual (criterio de 3 ejecuciones). |
| Polling | Helper `eventualmente(condicion, { intentos, intervaloMs })`. Defaults **15 intentos × 2 000 ms = 30 s** máximo, por entorno (`POLL_INTENTOS`, `POLL_INTERVALO_MS`) | Valores del PDF. La cadena más larga es de dos saltos de broker (`empleado.creado` → auth → `usuario.creado` → notificaciones); en el Reto 5 la colección esperaba 20 s. Se calibra en la Fase 6 midiendo el tiempo real y se anota en el README. Sin `sleep` fijo en ningún paso. |
| Condición negativa («eventualmente no puede iniciar sesión») | Se espera la señal **positiva** del evento y se verifica la consecuencia | Primero se comprueba que el login daba 200 (precondición). Luego se hace polling hasta que el login responde **401 con `La cuenta no está activa`** (mensaje específico, no cualquier 401) **y** aparece la notificación `CUENTA` «Su cuenta fue desactivada». Si en 30 s no aparece la notificación, el fallo dice «el evento no se procesó», no «el login falló». |
| Control del tiempo en vacaciones | Fechas de **mañana** + `POST /vacaciones/{id}/forzar-inicio` y `forzar-fin` | El scheduler solo mira fechas sin hora y corre cada minuto. Esperar al cron haría la prueba lenta (hasta 60 s) y acoplada a `VACACIONES_CRON`; con `fechaInicio` = hoy el cron puede adelantarse y el `forzar-inicio` respondería 400 (intermitente). Con mañana el cron no lo toca y los `forzar-*` disparan la misma transición y el mismo evento. Es la misma decisión que la colección del Reto 5. |
| Recuperación para un retirado | Se verifica lo observable: `recover-password` responde 200 con el mensaje genérico (anti-enumeración, contrato del Reto 5) **y** `reset-password` con un token de reset válido responde **400** «La cuenta no admite un cambio de contraseña en su estado actual» | El PDF dice «la recuperación falla». En este sistema el 200 genérico es intencional; lo que falla es el cambio. Se escribe así en el escenario para que se lea como documentación del comportamiento real. |
| Notificación de desvinculación | `CUENTA` «Su cuenta fue desactivada» tras `DELETE /empleados/{id}` | Desde el Reto 5 no existe la fila `DESVINCULACION`; el adiós es `cuenta.desactivada` (`RETIRO`). |
| Ubicación de reportes | Node y Python: `<servicio>/reports/`. Maven: rutas estándar de la herramienta (`target/surefire-reports/`, `target/failsafe-reports/`, `target/site/jacoco/jacoco.xml`). BDD: `e2e-tests/reports/` | El PDF pide una convención y respetarla. Mover los de Maven a `reports/` sería pelear con Surefire; Jenkins acepta globs. `reports/` se agrega al `.gitignore` raíz. |
| Contenedor de pruebas (opcional del Punto 6) | Servicio `bdd-tests` en el compose con **`profiles: ["tests"]`**, volumen `./e2e-tests/reports:/app/reports` | Con el profile, `docker compose up` normal no corre las pruebas. Se lanza con `docker compose --profile tests run --rm bdd-tests`. Se hace al final, solo si las fases obligatorias están cerradas. |

## Convención de reportes (contrato con el Reto 7)

| Suite | Comando (desde la carpeta indicada) | Resultados | Cobertura |
|---|---|---|---|
| `vacaciones-service` (unit) | `npm test` | `services/vacaciones-service/reports/junit.xml` | `services/vacaciones-service/reports/lcov.info` |
| `empleados-service` (unit) | `mvn test` | `services/empleados-service/target/surefire-reports/TEST-*.xml` | — |
| `empleados-service` (unit + integración) | `mvn verify` (requiere Docker) | `target/surefire-reports/TEST-*.xml` + `target/failsafe-reports/TEST-*.xml` | `services/empleados-service/target/site/jacoco/jacoco.xml` |
| `auth-service` (unit) | `pytest` | `services/auth-service/reports/junit.xml` | `services/auth-service/reports/coverage.xml` |
| `e2e-tests` (BDD) | `npm test`, con el sistema arriba | `e2e-tests/reports/junit.xml` | — (`cucumber.json` y `cucumber.html` en la misma carpeta) |

Los comandos `npm test` / `pytest` llevan las opciones en `package.json` / `pyproject.toml`
para que «un solo comando» sea literal. Las rutas exactas pueden ajustarse en la fase que las
implementa; si cambian, se cambian **aquí** y en el README en el mismo commit.

## Estructura objetivo de `e2e-tests/`

```
e2e-tests/
├── package.json             ← @cucumber/cucumber, script "test"
├── cucumber.js              ← perfiles: formatos html/json/junit, rutas, paths de features
├── .env.example             ← BASE_URL, ADMIN_USUARIO, ADMIN_PASSWORD, USER_PASSWORD, POLL_*
├── features/
│   ├── 00-humo.feature
│   ├── 01-seguridad.feature
│   ├── 02-onboarding.feature
│   ├── 03-vacaciones.feature
│   └── 04-offboarding.feature
├── step_definitions/
│   ├── comunes.steps.js     ← códigos de respuesta, cuerpo, autenticación
│   ├── empleados.steps.js
│   ├── cuentas.steps.js     ← login, reset, recover
│   └── vacaciones.steps.js
├── support/
│   ├── world.js             ← contexto por escenario
│   ├── http.js              ← wrapper de fetch que guarda la última respuesta
│   ├── polling.js           ← eventualmente()
│   ├── fabrica.js           ← IDs únicos, empleadoConCredenciales()
│   └── hooks.js             ← BeforeAll: health del Gateway; Before: World limpio
└── reports/                 ← generado, en .gitignore
```

## Fases

Cada fase indica qué entrega, de qué depende y qué criterio del PDF cierra. Las Fases 1, 2 y 3
tocan carpetas distintas y pueden ir en paralelo entre sí y con la Fase 4. Las Fases 5 a 7
dependen de la 4 y comparten `e2e-tests/step_definitions/`: si hay dos personas, una toma
onboarding y la otra vacaciones/offboarding **después** de que la Fase 6 deje el helper
`empleadoConCredenciales()` listo.

### Fase 0 — Plan y STATUS

- Este archivo y [`STATUS.md`](STATUS.md).
- `reports/` al `.gitignore` raíz.

No depende de nada.

### Fase 1 — `vacaciones-service`: unitarias y cobertura ≥ 70 % — criterio 1 (parte de 1.5)

Solo `services/vacaciones-service/`.

- Auditar `validar.test.js` y `transiciones.test.js`: qué cubren ya, que ninguno abra Postgres
  ni el broker.
- Completar las **cuatro validaciones** como tablas de casos:
  1. `fechaFin` anterior a `fechaInicio` → rechazo. Igual es válido (decisión del Reto 5; el
     PDF dice `fechaFin > fechaInicio`, se documenta la diferencia en el README).
  2. `fechaInicio` en el pasado → rechazo; hoy → válido. `hoy` se inyecta, nunca `new Date()`
     dentro del test.
  3. **Solapamiento** (`seSolapan` y `periodoQueBloquea`): empieza dentro, termina dentro, lo
     contiene, está contenido, idéntico, se toca por un día (inicio = fin del otro: **solapa**,
     el SQL usa `<=`), adyacente sin tocar (no solapa), y un período `CANCELADA` o
     `FINALIZADA` no bloquea.
  4. `diasHabiles`: mismo día hábil, mismo día sábado, cruza fin de semana, semana completa.
- Al menos un caso de error en `servicio.js` con dobles: repositorio falso que devuelve un
  conflicto, cliente de empleados que devuelve `RETIRADO` → error esperado, y el publisher
  falso **no** se llama.
- Script `npm test` con `--test-reporter=spec` a consola, `junit` a `reports/junit.xml`,
  `lcov` a `reports/lcov.info`, cobertura incluida solo sobre los cuatro archivos de dominio y
  `--test-coverage-lines=70` para que bajar del umbral falle el comando.
- Romper a propósito `fechaFin < fechaInicio` → `>`, correr, guardar la salida (qué test falla
  y con qué mensaje) para `EVIDENCIAS.md`, revertir.
- README del servicio: tabla de reportes, qué no se prueba (wiring de Express, `db.js`,
  `config.js`) y por qué.

Depende de la Fase 0.

### Fase 2 — `empleados-service`: unitarias, integración y JaCoCo — criterio 1 (parte de 1.5)

Solo `services/empleados-service/` (historial de subtree: commits normales, sin squash).

- Auditar los tests existentes: cuáles usan `@SpringBootTest`/H2 (integración disfrazada) y
  cuáles son Mockito puro. Reetiquetar o renombrar a `*IT` lo que levante contexto con base.
- Unitarias de `EmpleadoService` con Mockito (`EmpleadoRepository`, `DepartamentoClient`,
  `EmpleadoEventPublisher` como dobles):
  - email duplicado → `ConflictException`, el publisher no se invoca;
  - `numeroEmpleado` duplicado → `ConflictException`;
  - departamento inexistente → error del cliente propagado;
  - `retirar` → estado `RETIRADO`, `fechaRetiro` no nula, publica `empleado.retirado`;
  - retirar dos veces → 400 (transición inválida);
  - `actualizar` con email de otro empleado → conflicto.
- Integración `EmpleadoRepositoryIT` (Testcontainers `postgres:16`, Liquibase corre de verdad):
  - insertar dos empleados con el mismo email → `DataIntegrityViolationException` (la
    restricción existe en el schema, no solo en el código);
  - persistir un retiro y recuperarlo por el filtro de `estado=RETIRADO`;
  - `traducirViolacionDeUnicidad` con la violación real de Postgres.
- `pom.xml`: `jacoco-maven-plugin` (prepare-agent + report en `verify`), Failsafe para `*IT`,
  `org.testcontainers:postgresql` y `junit-jupiter` en test. Exclusiones de JaCoCo: DTOs,
  entidades, `config/`, la clase `main`. Quitar H2 si queda sin uso.
- README: tabla de reportes, Testcontainers vs H2 vs instancia dedicada, exclusiones.

Depende de la Fase 0. Requiere Docker para `mvn verify`, no para `mvn test`.

### Fase 3 — `auth-service`: reportes y la regla del retiro — criterio 1 (refuerzo)

Solo `services/auth-service/`.

- `requirements-dev.txt` con `pytest`, `pytest-cov`, `httpx`.
- `pyproject.toml`: `addopts` con `--junitxml=reports/junit.xml --cov=auth_service
  --cov-report=xml:reports/coverage.xml --cov-report=term`.
- Confirmar que `test_ciclo.py` cubre: iniciadas → retirado → finalizadas no reactiva;
  `vacaciones.finalizadas` desde `SUSPENDIDA_TEMPORAL` sí reactiva; token de reset expirado →
  400; un token de acceso usado como reset → 400; el hash no es la clave en claro. Completar lo
  que falte.
- Si `notificaciones-service` y `api-gateway` admiten los mismos flags sin tocar código, se les
  agregan reportes aquí mismo (opcional).

Depende de la Fase 0.

### Fase 4 — Proyecto BDD y escenario de humo — criterio 2 (0.5)

Carpeta nueva `e2e-tests/` y `.gitignore` raíz.

- `package.json`, `cucumber.js` con los tres formatos a `reports/`, `.env.example`.
- `support/world.js`, `support/http.js`, `support/polling.js`, `support/hooks.js`.
- Variables: `BASE_URL` (default `http://localhost:8080`), `ADMIN_USUARIO`, `ADMIN_PASSWORD`,
  `USER_PASSWORD` (la que se establece a los empleados creados; debe cumplir la política del
  Reto 5), `POLL_INTENTOS`, `POLL_INTERVALO_MS`. Ninguna credencial en el código.
- `00-humo.feature`: «Dado que el sistema está desplegado y operativo» hace `GET
  {BASE_URL}/health`; «Entonces la respuesta debe tener código 200». El paso de código se
  reutiliza en todas las features.
- README de `e2e-tests/`: por qué Cucumber.js, cómo funciona el World (una instancia por
  escenario, token y última respuesta).

Depende de la Fase 0. No necesita las Fases 1 a 3.

### Fase 5 — Seguridad y RBAC — criterio 3 (0.5)

`e2e-tests/features/01-seguridad.feature` y sus steps.

- El escenario dado: `GET /empleados` sin token → 401.
- Propios (mínimo tres, se apunta a cinco):
  - token malformado (`Bearer abc`) → 401; token con firma alterada → 401;
  - `USER` consulta `GET /empleados` → 200; `USER` hace `POST /empleados` → 403 y `DELETE
    /empleados/{id}` → 403;
  - `ADMIN` crea y consulta → 201 / 200;
  - (opcional) `USER` edita su perfil → 200 y el de otro → 403, como Esquema del escenario.
- `Antecedentes`: existe un empleado con credenciales y un departamento de prueba. Aquí entra
  una primera versión del helper `empleadoConCredenciales()` (con polling); la Fase 6 lo
  endurece.

Depende de la Fase 4.

### Fase 6 — Onboarding asíncrono — criterio 4 (1.0)

`e2e-tests/features/02-onboarding.feature`.

- Registro exitoso → **eventualmente** hay una notificación `SEGURIDAD` con token para ese
  empleado (prueba que auth creó la cuenta y publicó `usuario.creado`).
- Antes de activar, el login responde 401 «La cuenta no está activa» (la cuenta existe y está
  pendiente).
- Establecer contraseña con el token → 200; login → 200 y el `role` es `USER`.
- Datos inválidos como **Esquema del escenario**: departamento inexistente, campo obligatorio
  faltante, email mal formado, email duplicado → código esperado por fila (400/404/409 según el
  contrato existente; se confirma contra el servicio, no se asume).
- Medir el tiempo real de propagación (10 corridas) y fijar `POLL_*`; anotar en el README la
  medición y el margen.

Depende de la Fase 4 (y de la 5 para reutilizar pasos).

### Fase 7 — Vacaciones y offboarding — criterio 5 (1.0)

`e2e-tests/features/03-vacaciones.feature` y `04-offboarding.feature`.

- Antecedentes: «Dado que existe un empleado activo con credenciales establecidas» y «Y que
  estoy autenticado como "ADMIN"».
- Vacaciones:
  - programar un período (mañana → +3) → 201 y eventualmente notificación `VACACIONES`;
  - `fechaFin` anterior a `fechaInicio` → 400;
  - un segundo período solapado → rechazo (código del contrato);
  - **ciclo completo**: «Cuando programo un período de vacaciones que inicia de inmediato»
    (crea mañana + `forzar-inicio`), «Entonces eventualmente el empleado no puede iniciar
    sesión», «Cuando el período de vacaciones finaliza» (`forzar-fin`), «Entonces
    eventualmente el empleado puede iniciar sesión nuevamente».
- Offboarding:
  - retirar → eventualmente notificación `CUENTA` «Su cuenta fue desactivada»;
  - el retirado no puede iniciar sesión (401 «La cuenta no está activa»);
  - recuperación: 200 genérico y `reset-password` → 400;
  - aparece en `GET /empleados?estado=RETIRADO`.
- (Opcional, alto valor) el caso borde del Reto 5: retirado durante vacaciones, `forzar-fin`,
  el login sigue en 401.
- README: control del tiempo, condición negativa, qué pasos se reutilizaron.

Depende de las Fases 4 y 6.

### Fase 8 — Reportes, reproducibilidad y documentación — criterio 6 (0.5)

- Correr la BDD 3 veces seguidas sobre el mismo sistema (sin `down -v`); si algo es
  intermitente, encontrar la causa (casi siempre un polling corto o un ID repetido).
- Simular un fallo: cambiar un código esperado, verificar mensaje «esperado X, obtenido Y», el
  `<failure>` en `junit.xml`, el escenario en rojo en el HTML y `echo $LASTEXITCODE` ≠ 0.
  Revertir.
- README raíz, sección Reto 6: pirámide y reparto real de pruebas, qué es BDD y por qué
  Cucumber.js, prerrequisitos (Node 22, JDK 21 + Maven, Python 3.12, Docker), pasos exactos,
  la tabla de reportes de este plan, escenarios cubiertos.
- `docs/reto6/EVIDENCIAS.md`: regla rota detectada (Fases 1 y 2), cobertura medida, 3 corridas,
  fallo simulado.
- (Opcional) servicio `bdd-tests` con `profiles: ["tests"]` y volumen de reportes.

Depende de las Fases 1 a 7.

## Criterios de evaluación ↔ fases

| # | Elemento | Pts | Fase |
|---|---|---|---|
| 1 | Unitarias en dos lenguajes, test doubles, una integración con BD real, reportes, ≥ 70 %, caso de error | 1.5 | 1 + 2 + 3 |
| 2 | Proyecto BDD, World, variables de entorno, humo, justificación | 0.5 | 4 |
| 3 | 4+ escenarios de seguridad, Antecedentes | 0.5 | 5 |
| 4 | Onboarding con polling, parámetros justificados, independencia | 1.0 | 6 |
| 5 | Vacaciones, ciclo de suspensión, offboarding, control del tiempo | 1.0 | 7 |
| 6 | Un comando por suite, tres formatos, exit code, 3 corridas, tabla en README | 0.5 | 8 |

## Fuera de alcance de Reto 6 (a propósito)

- Jenkins, SonarQube o cualquier pipeline (Reto 7). Aquí solo se dejan los archivos.
- Pruebas en `departamentos-service`, `perfiles-service` y `notificaciones-service` más allá de
  lo que ya existe.
- Pruebas de carga, contrato (Pact) o mutación. La «trampa del 70 %» se responde con aserciones
  sobre reglas y con la regla rota a propósito, no con herramientas nuevas.
- Endpoints nuevos para facilitar las pruebas (p. ej. devolver el token de activación). Los
  `forzar-*` del Reto 5 son los únicos atajos de tiempo.
- Limpiar la base entre corridas de la BDD.
- Correr pruebas dentro de los Dockerfiles de los servicios.
