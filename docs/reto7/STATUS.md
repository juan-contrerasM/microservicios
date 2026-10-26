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
| 0 | Plan, este STATUS, y `CLAUDE.md` apuntando al Reto 7 | ✅ | Decisiones tomadas en el plan: socket con GID real, JCasC sobre `file:///workspace`, Sonar `26.10.0.133078-community`, gate `Reto7` al 70 %, E2E en el proyecto Compose `micro-ci`. |
| 1 | Jenkins en el compose: Dockerfile, plugins, socket, job `verificar-docker`, UI en `:9090` | ✅ | Build 3 de `verificar-docker` en SUCCESS (26 oct 2026): `docker` 27.5.1, Compose v2.32.4 y `docker ps`. Login `admin` por la API en `:9090`, sin asistente. Los tres jobs los crea JCasC. El id `docker-pipeline` no existe (404); el artefacto es `docker-workflow`. Debian 13 no trae el cliente en `docker.io`: la imagen agrega el binario estático. La clave JCasC del servidor Sonar es `sonarGlobalConfiguration`. |
| 2 | `services/empleados-service/Jenkinsfile`: Checkout, Build, Test, `junit`, JaCoCo, job como código | ✅ | Job creado por JCasC, sin tocarlo en la UI. Builds 2 a 5: Test Result 79 en verde. Cobertura de línea 88,07 % e instrucciones 88,41 % desde el build 2, así que el build 3 ya tiene la serie anterior. La gráfica de tests responde en `/job/empleados-service/test/trend`. Build 6, UNSTABLE: falla `HealthControllerTest.healthDevuelve200CuandoPostgreSqlEstaDisponible` con el mensaje «Fallo deliberado de la fase 2» (esperaba 418 y recibió 200). `skipStagesAfterUnstable` se saltó Sonar, Quality Gate, Package y E2E. La prueba quedó revertida en `main`. |
| 3 | SonarQube, `sonar-db`, bootstrap del gate y del token, stages Sonar y Quality Gate | 🔄 | Sonar en `:9000` healthy. `sonar-bootstrap` dejó el gate `Reto7` por defecto, solo `coverage` y `new_coverage` en LT 70, proyectos `empleados-service` y `vacaciones-service`, webhook a `http://jenkins:8080/sonarqube-webhook/` y el token en el volumen (45 bytes, lo lee Jenkins). Falta el stage del pipeline y el gate rojo provocado. |
| 4 | Package, E2E con reportes Cucumber, `wait-for-healthy.sh`, Jenkinsfile de `vacaciones-service` | 🔄 | Jenkinsfile, `sonar-project.properties` y `wait-for-healthy.sh` escritos. El job de vacaciones está creado. No se ha ejecutado. Siguen sin existir `03-vacaciones.feature` y `04-offboarding.feature`. |
| 5 | Tres corridas, cuatro fallos simulados, README, capturas en `EVIDENCIAS.md` | 🔄 | La sección Reto 7 del README está escrita, con la tabla de reportes. Faltan las tres corridas, los cuatro fallos y las capturas. |

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
