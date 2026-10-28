# Estado de implementación — Reto 7

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una fase. Ver el detalle de cada fase en
[`PLAN-RETO7.md`](PLAN-RETO7.md). Enunciado: [`reto7.pdf`](reto7.pdf).

El Reto 6 está cerrado; su estado vive en [`docs/reto6/STATUS.md`](../reto6/STATUS.md).
Este reto no cambia contratos de negocio. La Fase 4 publicó la suite BDD completa, incluidas
`03-vacaciones.feature` y `04-offboarding.feature`.

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Fase | Descripción | Estado | Notas |
|---|---|---|---|
| 0 | Plan, este STATUS, y `CLAUDE.md` apuntando al Reto 7 | ✅ | Falta: nada. No rehacer el plan. |
| 1 | Jenkins en el compose: Dockerfile, plugins, socket, job `verificar-docker`, UI en `:9090` | ✅ | Falta: nada. Evidencia: build 3 de `verificar-docker` en SUCCESS. |
| 2 | `services/empleados-service/Jenkinsfile`: Checkout, Build, Test, `junit`, JaCoCo, job como código | ✅ | Falta: nada. Evidencia: builds 2 a 5 en verde y build 6 UNSTABLE revertido. |
| 3 | SonarQube, `sonar-db`, bootstrap del gate y del token, stages Sonar y Quality Gate | ✅ | Falta: nada. Evidencia: builds 5 y 8 con gate OK, build 7 con gate ERROR revertido. |
| 4 | Package, E2E con reportes Cucumber, `wait-for-healthy.sh`, Jenkinsfile de `vacaciones-service` | ✅ | Falta: nada. Evidencia: `vacaciones-service` build 6 en SUCCESS. Empleados build 12 ya estaba en verde. |
| 5 | Tres corridas, cuatro fallos simulados, README, capturas en `EVIDENCIAS.md` | ✅ | Falta: nada. Empleados 13 a 15 y vacaciones 12 a 14 en SUCCESS. Los cuatro fallos quedaron revertidos. |

## Qué falta para continuar

Leer esta sección antes de tocar código. Las fases 0 a 4 están cerradas: no se repiten.
Los jobs hacen checkout de `file:///workspace`, rama `main`. Jenkins solo ve un cambio
después de commitearlo en `main`. Disparo automático: `./jenkins/install-git-hooks.sh` y luego
cada commit en `main` encola los dos jobs por API (Jenkins en `:9090`). **Build Now** sigue
válido. No commitear `.env`, tokens ni `e2e-tests/.ci-env`.
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

Cerrada con `vacaciones-service` build 6 en SUCCESS (26 oct 2026). Los builds 1 a 5
murieron en E2E: el broker con `eacces` en la cookie (1 y 2), MySQL en frío (3), Postgres
en frío (4) y el broker unhealthy otra vez (5). El build 6 levantó `micro-ci` con el broker
healthy y corrió la suite completa.

Comprobado en ese build:

- `docker image inspect` ve `micro/vacaciones-service:6` y `:latest` (la misma imagen).
- Test Result: 81 passed, 0 failed. Son 63 unitarias y 18 escenarios Cucumber, entre ellos
  «Programar vacaciones futuras», «Suspender y reactivar la cuenta durante el ciclo completo»,
  «Retirar conserva auditoría y bloquea la cuenta» y «La recuperación no vuelve a habilitar
  una cuenta retirada».
- El enlace «Reporte BDD» (`/job/vacaciones-service/6/Reporte_20BDD/`) sirve
  `cucumber.html` con «Ciclo de vacaciones» y «Retiro definitivo» en español.
- El artefacto `docker-logs.txt` está en el build. Coverage responde. Sonar dejó
  `vacaciones-service` en Passed: cobertura 98,8 %, 4 bugs, 3 vulnerabilidades, 0 code
  smells, duplicación 0,0 %.
- Tras el `down -v` no quedó ningún contenedor `micro-ci`. Siguen `jenkins_data`,
  `sonar_bootstrap` y los volúmenes de Sonar. Jenkins y Sonar quedaron arriba.

### Fase 5

Falta: nada. El README raíz ya tenía la sección Reto 7 y la tabla de reportes. El
Hook `post-commit` en `jenkins/git-hooks/` (instalar con `./jenkins/install-git-hooks.sh`).
Las capturas están en
[`EVIDENCIAS.md`](EVIDENCIAS.md). El volumen `jenkins_data` no se borró entre corridas.

Tres SUCCESS seguidas de cada pipeline:

- `empleados-service` builds 13, 14 y 15 (26 oct 2026).
- `vacaciones-service` builds 12, 13 y 14 (27 oct 2026).

Antes de la racha de vacaciones hubo dos fallos con causa medida, ya corregidos en `main`:

- Build 7, UNSTABLE. El escenario «Suspender y reactivar la cuenta durante el ciclo
  completo» murió con `fetch failed` en `POST .../forzar-fin`. El gateway no registró el
  proxy: el corte fue TCP y la petición no llegó al servicio. `e2e-tests/support/http.js`
  reintenta solo errores de red.
- Build 8, FAILURE en la espera de salud, antes de las pruebas. `empleados-service`
  arrancó en 287 s. Con intervalo de 5 s y 20 reintentos el healthcheck se agotaba cerca
  del segundo 220, y Docker lo dejó unhealthy aunque después escuchara. El broker también
  se pasó de su presupuesto. `jenkins/docker-compose.ci.yml` deja el `start_period` de
  empleados en 360 s (30 reintentos) y el del broker en 150 s (15 reintentos).
  `jenkins/wait-for-healthy.sh` espera 480 s, que cubre ese arranque.
- El build 11 quedó colgado cuando Docker se cayó de noche y se abortó. No entra en la
  racha. La racha válida es 12, 13 y 14.

Cuatro fallos, uno por uno, capturados y revertidos. `main` vuelve a tener la suite, el
Dockerfile y el humo en 200:

1. `empleados-service` build 16, UNSTABLE. Test en amarillo y las etapas siguientes no
   corrieron. Test Result nombra `healthDevuelve200CuandoPostgreSqlEstaDisponible`:
   «Fallo deliberado de la fase 5», expected 418, was 200.
2. `empleados-service` build 18, FAILURE. JaCoCo en 3,36 % de líneas. Quality Gate
   `ERROR`; Package y E2E no corrieron. El build 17 excluyó todo el código de Sonar, la
   medida no bajó y se abortó: no cuenta como el fallo del gate.
3. `vacaciones-service` build 15, FAILURE. Package en rojo: `RUN this-command-does-not-exist`
   salió 127. E2E no corrió.
4. `vacaciones-service` build 16, UNSTABLE. Se detiene en E2E Tests. Cucumber nombra
   «El borde responde cuando el sistema está operativo»: esperado 201, obtenido 200.

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
