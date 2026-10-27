# Estado de implementación — Reto 7

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una fase. Ver el detalle de cada fase en
[`PLAN-RETO7.md`](PLAN-RETO7.md). Enunciado: [`reto7.pdf`](reto7.pdf).

El Reto 6 sigue con las fases 7 y 8 abiertas; su estado vive en
[`docs/reto6/STATUS.md`](../reto6/STATUS.md). Este reto no cambia contratos de negocio. La
Fase 4 publica la suite BDD que ya exista; el escenario de offboarding entra cuando el Reto 6
cierre su Fase 7.

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Fase | Descripción | Estado | Notas |
|---|---|---|---|
| 0 | Plan, este STATUS, y `CLAUDE.md` apuntando al Reto 7 | ✅ | Falta: nada. No rehacer el plan. |
| 1 | Jenkins en el compose: Dockerfile, plugins, socket, job `verificar-docker`, UI en `:9090` | ✅ | Falta: nada. Evidencia: build 3 de `verificar-docker` en SUCCESS. |
| 2 | `services/empleados-service/Jenkinsfile`: Checkout, Build, Test, `junit`, JaCoCo, job como código | ✅ | Falta: nada. Evidencia: builds 2 a 5 en verde y build 6 UNSTABLE revertido. |
| 3 | SonarQube, `sonar-db`, bootstrap del gate y del token, stages Sonar y Quality Gate | ✅ | Falta: nada. Evidencia: builds 5 y 8 con gate OK, build 7 con gate ERROR revertido. |
| 4 | Package, E2E con reportes Cucumber, `wait-for-healthy.sh`, Jenkinsfile de `vacaciones-service` | 🔄 | Falta una corrida SUCCESS de `vacaciones-service`. Empleados build 12 ya está en verde. El detalle está en «Qué falta para continuar». |
| 5 | Tres corridas, cuatro fallos simulados, README, capturas en `EVIDENCIAS.md` | 🔄 | Falta todo menos el README. No empezar hasta cerrar la fase 4. El detalle está abajo. |

## Qué falta para continuar

Leer esta sección antes de tocar código. Las fases 0 a 3 están cerradas: no se repiten.
Los jobs hacen checkout de `file:///workspace`, rama `main`. Jenkins solo ve un cambio
después de commitearlo en `main`. No commitear `.env`, tokens ni `e2e-tests/.ci-env`.
No hacer `docker compose down -v` del proyecto principal: borra `jenkins_data`,
`sonar_bootstrap` y las bases de negocio. El `down -v` del E2E es solo `-p micro-ci`,
con `jenkins/docker-compose.ci.yml`, que marca esos volúmenes como externos.

### Fase 0

Falta: nada. El plan, este archivo y `CLAUDE.md` ya apuntan al Reto 7.

### Fase 1

Falta: nada. El build 3 de `verificar-docker` terminó en SUCCESS (26 oct 2026): `docker`
27.5.1, Compose v2.32.4 y `docker ps`. El login entra por la API en `:9090`, sin asistente.
Los tres jobs los crea JCasC. El id `docker-pipeline` no existe; el artefacto es
`docker-workflow`. Debian 13 no trae el cliente en `docker.io`: la imagen agrega el binario
estático. La clave JCasC del servidor Sonar es `sonarGlobalConfiguration`.

### Fase 2

Falta: nada. El job de empleados lo crea JCasC. Builds 2 a 5: Test Result con 79 pruebas
en verde. Cobertura de línea 88,07 % e instrucciones 88,41 % desde el build 2. La gráfica
responde en `/job/empleados-service/test/trend`. El build 6, UNSTABLE, falló
`HealthControllerTest.healthDevuelve200CuandoPostgreSqlEstaDisponible` con el mensaje
«Fallo deliberado de la fase 2». La prueba quedó revertida en `main`.

### Fase 3

Falta: nada. Gate `Reto7` por defecto: `coverage` y `new_coverage` en LT 70. El build 5
dejó el dashboard en Passed (cobertura 83,9 %, 0 bugs, 1 vulnerabilidad, 2 code smells,
duplicación 6,4 %) y el stage terminó `Quality gate is OK`. El build 7, con la suite
recortada, abortó en Quality Gate (`ERROR`) y se revirtieron las pruebas en `5535f3c`.
El build 8 volvió a `Quality gate is OK`. El token vive en el volumen `sonar_bootstrap`
y no entra a Git.

### Fase 4

Ya está hecho, y no hay que reescribirlo:

- Los dos Jenkinsfiles, `jenkins/wait-for-healthy.sh` y el `sonar-project.properties` de
  vacaciones. El job de vacaciones ya lo crea JCasC.
- `empleados-service` build 12 en SUCCESS: imagen `micro/empleados-service:12` y `:latest`,
  12 escenarios Cucumber en español (Humo, Seguridad y control de acceso, Onboarding
  asíncrono), enlace «Reporte BDD» y `down -v` solo de `micro-ci`. Con eso ese pipeline
  ya cumplió la corrida verde. No hace falta repetirlo para cerrar la fase.
- El broker de CI es la imagen `micro/rabbitmq-ci` (`jenkins/rabbitmq/`). En Docker Desktop
  el volumen de `/var/lib/rabbitmq` deja `.erlang.cookie` como root y RabbitMQ sale con
  `eacces`. El entrypoint escribe la cookie en `/tmp/rabbit-home`.
- `jenkins/docker-compose.ci.yml` alarga el `start_period` de las bases y de los servicios
  en frío, y el healthcheck del broker corre como el usuario `rabbitmq`.

Falta, en este orden:

1. Volver a levantar Jenkins y Sonar. Los contenedores `jenkins`, `sonarqube` y `sonar-db`
   quedaron parados con código 255. `docker compose up -d` alcanza. No borra volúmenes.
2. Conseguir un build SUCCESS de `vacaciones-service` de punta a punta. Los builds 1 a 5
   terminaron en FAILURE, todos en E2E, con Quality Gate y Package ya en verde en el
   build 5:
   - Builds 1 y 2: el broker murió con `eacces` al leer `.erlang.cookie`.
   - Build 3: `micro-ci-database-departamentos-1` unhealthy. MySQL en frío tardó más que
     el `start_period` de desarrollo. El init sí llegó a «ready for connections».
   - Build 4: `micro-ci-database-empleados-1` unhealthy. Misma causa, en Postgres.
   - Build 5: `micro-ci-message-broker-1` unhealthy. El artefacto `docker-logs.txt` de
     ese build quedó vacío, así que la causa del healthcheck no quedó registrada.
3. Si el build 6 vuelve a marcar el broker unhealthy, mirar el log de ese contenedor
   antes de subir timeouts. El healthcheck es `gosu rabbitmq env HOME=/tmp/rabbit-home
   rabbitmq-diagnostics -q ping`. Un arranque solo del broker sí queda healthy; el fallo
   aparece cuando sube el stack completo. No usar `docker compose down -v` del proyecto
   principal para «limpiar».
4. En el build verde, comprobar `docker image inspect` de `micro/vacaciones-service:<N>`
   y `:latest`, el reporte Cucumber en español sin abrir la consola, el enlace «Reporte
   BDD» (la URL lleva `Reporte_20BDD`), el artefacto `docker-logs.txt`, y que el `down -v`
   solo borró volúmenes `micro-ci_*`. Jenkins y Sonar tienen que seguir arriba.
5. En el mismo commit, marcar esta fase ✅ y agregar la sección de la fase 4 en
   `docs/reto7/EVIDENCIAS.md`.

No crear `e2e-tests/features/03-vacaciones.feature` ni `04-offboarding.feature`. No
existen, y cerrarlas es el Reto 6 (fases 7 y 8), fuera de este reto. El E2E de la fase 4
corre contra `00-humo`, `01-seguridad` y `02-onboarding`. El escenario de offboarding no
se marca ✅ hasta que el Reto 6 deje esas features. Eso no bloquea la corrida verde de
vacaciones ni el README de la fase 5.

### Fase 5

No empezar hasta que la fase 4 tenga el build verde de vacaciones. El README raíz ya
tiene la sección Reto 7 y la tabla de reportes. Falta:

1. Tres corridas SUCCESS seguidas de cada pipeline, sobre el mismo volumen `jenkins_data`.
   No borrarlo entre corridas. Si una sale intermitente, anotar aquí la causa (espera de
   salud, lock `ecosistema-e2e`, o un `down -v` que no corrió) y corregirla.
2. Cuatro fallos, uno por uno, revertidos después de capturar la pantalla:
   - Una unitaria rota se queda en **Test**, amarillo, y Test Result nombra la prueba.
   - Cobertura bajo 70 % se detiene en **Quality Gate**.
   - Un `RUN` inválido en el Dockerfile se detiene en **Package**, rojo.
   - Un escenario BDD con el código esperado cambiado se detiene en **E2E Tests** y
     Cucumber nombra el escenario en español.
3. Capturas en `docs/reto7/EVIDENCIAS.md`: pipeline en verde, Test Result, Coverage,
   reporte BDD en español, y un build fallido con la prueba o el escenario identificado.
   Hoy `EVIDENCIAS.md` tiene las fases 1 a 3 y dice que las capturas de la fase 5 siguen
   pendientes.

El `pollSCM` opcional no hace falta para cerrar la fase.

## Decisiones técnicas del enunciado

Versión corta. El «por qué» largo está en `PLAN-RETO7.md` y, al cerrar la fase, se copia al
README que el PDF pide.

| Decisión | Estado | Elección tomada |
|---|---|---|
| Jenkins | Tomada | `jenkins/jenkins:lts-jdk21`, puerto de host 9090, wizard apagado, admin por JCasC. |
| Docker | Tomada | Socket del host. El entrypoint usa el GID real del socket. |
| Jobs | Tomada | JCasC + Job DSL, checkout `file:///workspace`, rama `main`. |
| Agentes | Tomada | `maven:3.9-eclipse-temurin-21` y `node:22`, `reuseNode true`. Caché en `jenkins_m2` y `jenkins_npm`. |
| Pruebas rotas | Tomada | `UNSTABLE` + `skipStagesAfterUnstable()`. El reporte se publica igual. |
| SonarQube | Tomada | `sonarqube:26.10.0.133078-community` y Postgres propio. El tag `lts-community` del PDF ya no existe. |
| Quality Gate | Tomada | Gate `Reto7`, cobertura ≥ 70 % total y en código nuevo. Exclusiones iguales a las del Reto 6. |
| Token | Tomada | Lo genera `sonar-bootstrap` y queda en el volumen `sonar_bootstrap`. No entra a Git. |
| Imágenes | Tomada | `micro/<servicio>:<BUILD_NUMBER>` y `:latest`. Sin registry. |
| E2E | Tomada | Proyecto `micro-ci`, Gateway en el 18080, `down -v` solo de ese proyecto, lock `ecosistema-e2e`. |
| Dos lenguajes | Tomada | Empleados (Java) y vacaciones (Node). |

## Cómo actualizar este archivo

1. Cambia el estado de la fase que avanzaste (⬜ → 🔄 → ✅).
2. Si tomaste una decisión distinta a la del plan, anótalo en «Notas» y actualiza la tabla de
   decisiones y, si cambió una ruta o un parser, la tabla de reportes del plan.
3. Una fase no se marca ✅ solo porque el contenedor arranca: la Fase 1 necesita el job de
   `docker ps` en verde, la Fase 2 la pestaña Test Result, la Fase 3 el gate en Passed y un
   gate rojo provocado, la Fase 4 el escenario en español dentro de Jenkins, y la Fase 5 las
   cuatro capturas de fallo en la etapa que corresponde.
4. Si un build sale intermitente, no se sube el timeout a ciegas: se anota la causa aquí.
