# Plan de implementación — Reto 7: Integración continua con Jenkins

Basado en [`docs/reto7/reto7.pdf`](reto7.pdf). Este documento traza el plan en fases para que el
equipo pueda repartirse el trabajo sin pisarse. El avance real (no lo planeado) se registra en
[`STATUS.md`](STATUS.md). El contrato de negocio sigue siendo el del Reto 5 y las pruebas del
Reto 6: este reto no inventa rutas, códigos ni eventos. Si el pipeline descubre un bug, se
corrige en el servicio con su test, igual que en el Reto 6.

El Reto 6 no está cerrado. Las fases 0 a 6 de [`docs/reto6/STATUS.md`](../reto6/STATUS.md) ya
dejaron los reportes que este pipeline publica. Las fases 7 y 8 (vacaciones, offboarding,
tres corridas y `EVIDENCIAS.md`) siguen abiertas. La Fase 4 de este plan las necesita para
poder mostrar en Jenkins el escenario «el empleado retirado no puede iniciar sesión». Hasta
que existan `e2e-tests/features/03-vacaciones.feature` y `04-offboarding.feature`, la etapa
E2E corre lo que ya está: humo, seguridad y onboarding.

## Qué hay que resolver (y qué no)

Hoy el equipo compila, prueba y construye imágenes a mano. Los XML y el HTML del Reto 6 se
quedan en el disco de quien corrió el comando. Jenkins tiene que hacer ese trabajo en cada
ejecución y dejar el resultado en su interfaz: qué prueba falló, cuánta cobertura hay y qué
escenario de negocio se rompió.

| Pieza | Lo que ya existe | Lo que falta para el Reto 7 |
|---|---|---|
| `empleados-service` (Java 21, Maven) | `mvn test` / `mvn verify`, Surefire, Failsafe, JaCoCo en `target/site/jacoco/jacoco.xml`. Dockerfile multi-stage | `Jenkinsfile`, etapa Sonar, imagen etiquetada por el pipeline |
| `vacaciones-service` (Node 22) | `npm test` escribe `reports/junit.xml` y `reports/lcov.info`, umbral de líneas 70 % | `Jenkinsfile` en otro lenguaje, `sonar-project.properties` |
| `auth-service` (Python) | `pytest` escribe `reports/junit.xml` y `reports/coverage.xml` | Fuera del mínimo (el PDF pide dos Jenkinsfiles) |
| `e2e-tests/` | Cucumber.js, `reports/junit.xml`, `cucumber.json`, `cucumber.html`. Features 00, 01 y 02 | Entrar al pipeline. Features 03 y 04 siguen pendientes del Reto 6 |
| `docker-compose.yml` | Red `microservices-network`, Gateway en `${GATEWAY_PORT:-8080}` | Servicios `jenkins`, `sonarqube`, `sonar-db` y el bootstrap de Sonar |
| Jenkins / Sonar | No existen | Todo: Puntos 1 a 5 |

Los Dockerfiles de los servicios siguen sin correr pruebas. La imagen es el artefacto de
producción; quien ejecuta `mvn verify` y `npm test` es el pipeline.

1. **Un Jenkinsfile por servicio, dentro de su carpeta.** El mínimo son dos, en lenguajes
   distintos: `services/empleados-service/Jenkinsfile` (Java) y
   `services/vacaciones-service/Jenkinsfile` (Node). El workspace del job es la raíz del
   monorepo, así que cada Jenkinsfile entra a su carpeta con `dir(...)`.
2. **Jenkins y SonarQube viven en el compose**, en la misma red que el resto. No se instalan
   en el host. `docker compose up --build` los levanta.
3. **El pipeline publica antes de decidir el color del build.** Las pruebas pueden fallar y
   el `post { always { } }` igual publica JUnit, cobertura y el reporte Cucumber. El paso
   `junit` (o el Quality Gate) es quien marca el build.

## Decisiones técnicas ya tomadas

Si alguien quiere cambiarlas, que lo discuta con el equipo antes de tocar `jenkins/Dockerfile`,
el `docker-compose.yml` raíz o cualquiera de los dos Jenkinsfiles.

| Decisión | Elección | Por qué |
|---|---|---|
| Imagen de Jenkins | **`jenkins/jenkins:lts-jdk21`** | El PDF acepta `lts-jdk17` o `lts-jdk21`. Empleados ya es Java 21. El JDK de la imagen de Jenkins no compila el servicio: solo corre el controlador. |
| Plugins en el Dockerfile | `workflow-aggregator`, `docker-workflow`, `pipeline-stage-view`, `git`, `configuration-as-code`, `job-dsl`, `sonar`, `junit`, `coverage`, `cucumber-reports`, `htmlpublisher` y **`lockable-resources`** | El PDF nombra `docker-pipeline` y `docker-workflow`. En el update center el artefacto es uno solo: `docker-workflow` (nombre visible «Docker Pipeline»). Pedir `docker-pipeline.hpi` responde 404. `lockable-resources` evita que los dos pipelines levanten el ecosistema a la vez. Las versiones las resuelve `jenkins-plugin-cli` contra esa LTS. |
| Docker dentro de Jenkins | **Socket del host** (`/var/run/docker.sock`), con un entrypoint que lee el GID real del socket y mete a `jenkins` en ese grupo. El cliente es el binario estático `docker` 27.5.1 en `/usr/bin/docker` | El PDF recomienda el socket frente a Docker-in-Docker. `usermod -aG docker` del ejemplo falla en Docker Desktop (Windows): el GID del socket no es el del grupo `docker` que se crea dentro de la imagen. En Debian 13, `docker.io` instala `dockerd` y no el cliente, así que la imagen agrega el binario estático. |
| Asistente inicial | **Apagado** (`runSetupWizard=false`). El usuario administrador lo crea JCasC | Los plugins ya vienen en la imagen. No queda `initialAdminPassword`. La contraseña es `JENKINS_ADMIN_PASSWORD` del `.env` (referencia académica en `.env.example`, igual que `AUTH_ADMIN_PASSWORD`). |
| Jobs | **JCasC + Job DSL**, leyendo el monorepo montado en `/workspace` con `file:///workspace` | El PDF asume un repositorio Git por servicio. Aquí hay un solo repo (`github.com/juan-contrerasM/microservicios`, rama `main`) y los Jenkinsfiles viven en `services/<servicio>/Jenkinsfile`. Clonar GitHub exigiría una credencial dentro de Jenkins para que `up` deje los jobs listos. El bind mount del working tree permite el checkout en cuanto el contenedor arranca. El webhook de GitHub queda en la Fase 5, opcional. |
| Dónde corren Build y Test | **Agente Docker efímero**, `reuseNode true` | Maven, Node y el scanner no se instalan en la imagen de Jenkins. Empleados: `maven:3.9-eclipse-temurin-21`. Vacaciones y la BDD: `node:22`. El `args` del agente incluye la red de Compose (para resolver `sonarqube`) y el socket (para Testcontainers). |
| Red de Compose | Se fija `name: microservicios` en el compose (el nombre que ya usa esta carpeta) y `DOCKER_NETWORK=microservicios_microservices-network` en el servicio `jenkins` | Así un clon en otra carpeta no cambia el nombre de los volúmenes que ya existen, y el agente sabe a qué red unirse para hablar con SonarQube. |
| Testcontainers en el agente | `TESTCONTAINERS_RYUK_DISABLED=true` y `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`, más `--add-host=host.docker.internal:host-gateway` | El Reto 6 ya lo vio en WSL: Ryuk se queda sin DNS y el agente no alcanza el Postgres que el daemon publicó en el host. `mvn test` sigue sin Docker; `mvn verify` lo necesita. |
| Caché de dependencias | Volúmenes nombrados `jenkins_m2` (`/root/.m2`) y `jenkins_npm` (`/root/.npm`) | Si no, cada build baja Maven y npm desde cero. |
| Fallo de pruebas | **`UNSTABLE`**, y `options { skipStagesAfterUnstable() }` | Convención del PDF: no compila = `FAILURE` (rojo); pruebas rojas = `UNSTABLE` (amarillo). Sin `skipStagesAfterUnstable` Jenkins seguiría a Package y a E2E con el código ya roto. Un servicio con pruebas fallidas no se empaqueta. |
| Publicar aunque el comando falle | Maven: `mvn verify -Dmaven.test.failure.ignore=true`. Node y Cucumber: el comando termina en `\|\| true`. Después, `junit` marca el build | Si el comando aborta el stage, el `post` no publica y se pierde el nombre de la prueba. El umbral de líneas de vacaciones (70 %) lo vuelve a exigir el Quality Gate; el `\|\| true` solo evita perder el XML. |
| Cobertura en Jenkins | Empleados: `recordCoverage` con parser **`JACOCO`** sobre `target/site/jacoco/jacoco.xml`. Vacaciones: parser **`LCOV`** sobre `reports/lcov.info` | Esas rutas son las del Reto 6. Vacaciones no usa `nyc`: el reporter `lcov` ya lo escribe `node --test`. Si la versión del plugin `coverage` rechaza `LCOV`, la misma fase convierte a Cobertura XML y cambia el parser a `COBERTURA`; el cambio se anota aquí y en el README. |
| SonarQube | **`sonarqube:26.10.0.133078-community`** + Postgres `postgres:16-alpine` en `sonar-db` | El tag `lts-community` del PDF ya no existe: Community Build se publica como `community` y no tiene LTS. Se fija el tag con número (el que hoy apunta `community`) para que un `pull` de la semana que viene no cambie el servidor. `sonar-db` no publica puerto. Es otra base: no se reutiliza `database-empleados` ni ninguna de negocio. |
| Quality Gate | Gate propio **`Reto7`**, cobertura total ≥ 70 % y cobertura en código nuevo ≥ 70 %, gate por defecto | «Sonar way» exige 80 % en código nuevo y en el primer análisis el periodo de código nuevo puede quedar vacío, así que el gate pasaría sin mirar nada. El 70 % es el umbral del Reto 6 y el que pide el PDF. |
| Qué cuenta para el 70 % | Las mismas exclusiones del Reto 6. Vacaciones: `sonar.coverage.exclusions` de todo `src/` salvo `validar.js`, `fechas.js`, `transiciones.js` y `servicio.js`. Empleados: las exclusiones JaCoCo del `pom.xml` (DTOs, entidad, `config`, `main`, records de evento) repetidas en `sonar.coverage.exclusions` | Sonar mide el árbol completo. Sin esas exclusiones el gate castiga el wiring que el Reto 6 dejó fuera a propósito, y un 100 % de dominio puede verse como un suspenso. |
| Token de Sonar | Volumen `sonar_bootstrap` con el token, montado en Jenkins en `/run/sonar-bootstrap` (solo lectura). Lo escribe el servicio de un solo uso `sonar-bootstrap` | No va en el Jenkinsfile ni en Git. Sonar no deja releer un token ya emitido. El script espera `/api/system/status` = `UP`, cambia `admin` / `admin` a `SONAR_ADMIN_PASSWORD`, crea proyectos, gate y webhook, revoca el token llamado `jenkins` si existe y escribe uno nuevo cuando el archivo falta o `/api/authentication/validate` lo rechaza. `down -v` borra volumen y token juntos. |
| Webhook | `http://jenkins:8080/sonarqube-webhook/` (puerto interno; en el host Jenkins es el **9090**) | `waitForQualityGate` no termina si Sonar no avisa a Jenkins. El servidor SonarQube en JCasC se llama `sonarqube`, URL `http://sonarqube:9000`, y el stage usa `withSonarQubeEnv('sonarqube')`. |
| Scanner | Empleados: `sonar-maven-plugin` invocado solo en el stage, con versión fijada en el `pom.xml` (no en el ciclo de `package`). Vacaciones: imagen `sonarsource/sonar-scanner-cli` y `sonar-project.properties` en la carpeta del servicio | El scanner no entra al Dockerfile de producción. Sonar no ejecuta las pruebas: lee el JaCoCo o el lcov que ya generó el stage Test. |
| Quality Gate rojo | `abortPipeline: true` dentro de `timeout(time: 5, unit: 'MINUTES')` | El PDF pide que el pipeline se detenga. Eso es `FAILURE`, distinto del amarillo de una prueba unitaria. |
| Imágenes Docker | `micro/<servicio>:<BUILD_NUMBER>` y `micro/<servicio>:latest` | El daemon del pipeline y el de `docker compose` son el mismo. Un registry en el `:5000` obliga a marcarlo inseguro y no aporta mientras el compose construye en local. El registry del PDF queda fuera del camino obligatorio. |
| Multi-stage | Se usan los Dockerfiles que ya hay. Empleados ya es multi-stage; vacaciones sigue en una sola etapa | Reescribir el Dockerfile de vacaciones no es un criterio de este reto. |
| E2E y los datos del equipo | Proyecto Compose **`micro-ci`** (`docker compose -p micro-ci`). Al final, `docker compose -p micro-ci down -v` | Sin `-p`, el nombre de proyecto es la carpeta y ese `down -v` borra `vol-empleados`, `vol-auth` y el resto del trabajo local. Con otro nombre, los volúmenes son `micro-ci_vol-*`. |
| Puertos del stack de CI | El mismo `docker-compose.yml`, con los puertos de host que ya son variables: `GATEWAY_PORT=18080`, `RABBITMQ_PORT=5673`, `RABBITMQ_MANAGEMENT_PORT=15673`, `MAILHOG_UI_PORT=18025` | Esos host ports ya están parametrizados. El `:8080` del equipo puede seguir arriba. Dentro de la red los puertos no cambian. |
| `BASE_URL` de la BDD | `http://host.docker.internal:18080` | El proyecto `micro-ci` tiene otra red, así que Jenkins no resuelve `api-gateway` de esa corrida. Docker Desktop (Windows) ya resuelve `host.docker.internal`. El servicio `jenkins` lleva `extra_hosts: ["host.docker.internal:host-gateway"]` para quien corra el mismo compose en Linux. |
| Esperar salud | `jenkins/wait-for-healthy.sh` consulta `docker compose -p micro-ci ps` hasta que ningún healthcheck sigue en `starting` o `unhealthy`, tope **240 s** | `GET /health` del Gateway responde 200 aunque un backend esté caído (decisión del Reto 3). Esperar solo eso daría falsos verdes. `up -d` regresa cuando los `depends_on: service_healthy` del compose se cumplen; el script cubre lo que el Gateway no espera. |
| Dónde corre la BDD | En el agente `node:22`, con `npm test` de `e2e-tests/`, el workspace del job | El perfil `bdd-tests` del Reto 6 no se implementó. Si la suite corriera dentro de otro contenedor, el XML se quedaría ahí y `junit` no lo vería. |
| Reportes E2E | `junit` sobre `e2e-tests/reports/junit.xml` (sin `allowEmptyResults`), `cucumber(...)` con `buildStatus: 'UNSTABLE'`, `publishHTML` del `cucumber.html`, y `archiveArtifacts` de `docker-logs.txt` | Las tres publicaciones del PDF. Si el sistema no levanta y no hay XML, el build falla: un resultado vacío escondería precisamente ese caso. Los logs archivados dicen por qué. |
| Los dos E2E a la vez | `lock('ecosistema-e2e')` en el stage E2E de ambos Jenkinsfiles | Comparten el proyecto `micro-ci` y el puerto 18080. `disableConcurrentBuilds` solo cubre un job. |
| Limpieza | El `down -v` va en el `post { always }` del stage E2E, también cuando la espera de salud falla | Un stack `micro-ci` a medias bloquea el puerto y el lock de la siguiente corrida. |
| Credenciales | `JENKINS_ADMIN_*`, `SONAR_ADMIN_PASSWORD` y el token de Sonar por entorno o por el volumen de bootstrap. El stack `micro-ci` reutiliza el `.env` raíz (`JWT_SECRET`, `AUTH_ADMIN_*`) | Nada de eso se escribe en el Jenkinsfile. La BDD recibe `BASE_URL`, `ADMIN_USUARIO`, `ADMIN_PASSWORD` y `USER_PASSWORD` por entorno del stage; los valores de admin son los de la semilla. |

## Convención de reportes (la del Reto 6, leída por Jenkins)

Los comandos no cambian. El pipeline los ejecuta dentro de `dir(...)` y publica estas rutas,
relativas a la carpeta del servicio (o a `e2e-tests/`):

| Suite | Comando | Resultados (`junit`) | Cobertura (`recordCoverage` / Sonar) |
|---|---|---|---|
| `empleados-service` | `mvn verify -Dmaven.test.failure.ignore=true` | `target/surefire-reports/*.xml`, `target/failsafe-reports/*.xml` | `target/site/jacoco/jacoco.xml` (JaCoCo) |
| `vacaciones-service` | `npm test \|\| true` | `reports/junit.xml` | `reports/lcov.info` (LCOV) |
| `e2e-tests` | `npm test \|\| true`, con `BASE_URL` del stack `micro-ci` | `e2e-tests/reports/junit.xml` | — (`cucumber.json` y `cucumber.html` en la misma carpeta) |

`auth-service` ya escribe `reports/junit.xml` y `reports/coverage.xml`. No entra al mínimo. Si
sobra tiempo, un tercer Jenkinsfile reutiliza el parser `COBERTURA`.

## Estructura objetivo

```
jenkins/
├── Dockerfile                 ← lts-jdk21, plugins, cliente docker.io
├── docker-entrypoint.sh       ← GID real del socket, luego jenkins.sh como usuario jenkins
├── casc.yaml                  ← seguridad local y jobs de los dos servicios
├── init-sonar.sh              ← gate, proyectos, webhook, token
└── wait-for-healthy.sh        ← healthchecks del proyecto micro-ci
services/empleados-service/Jenkinsfile
services/vacaciones-service/Jenkinsfile
services/vacaciones-service/sonar-project.properties
```

Fragmento de servicios que la Fase 1 y la Fase 3 agregan al `docker-compose.yml` (el resto del
archivo no se reescribe):

```yaml
name: microservicios

services:
  jenkins:
    build: ./jenkins
    container_name: jenkins
    ports:
      - "9090:8080"
      - "50000:50000"
    volumes:
      - jenkins_data:/var/jenkins_home
      - ./:/workspace
      - /var/run/docker.sock:/var/run/docker.sock
      - sonar_bootstrap:/run/sonar-bootstrap:ro
    environment:
      JAVA_OPTS: -Djenkins.install.runSetupWizard=false
      CASC_JENKINS_CONFIG: /var/jenkins_home/casc_configs
      JENKINS_ADMIN_ID: ${JENKINS_ADMIN_ID:-admin}
      JENKINS_ADMIN_PASSWORD: ${JENKINS_ADMIN_PASSWORD:-admin}
      DOCKER_NETWORK: microservicios_microservices-network
    extra_hosts:
      - "host.docker.internal:host-gateway"
    networks:
      - microservices-network

  sonarqube:
    image: sonarqube:26.10.0.133078-community
    container_name: sonarqube
    ports:
      - "9000:9000"
    environment:
      SONAR_JDBC_URL: jdbc:postgresql://sonar-db:5432/sonar
      SONAR_JDBC_USERNAME: sonar
      SONAR_JDBC_PASSWORD: ${SONAR_JDBC_PASSWORD:-sonar}
    volumes:
      - sonarqube_data:/opt/sonarqube/data
      - sonarqube_logs:/opt/sonarqube/logs
      - sonarqube_extensions:/opt/sonarqube/extensions
    depends_on:
      sonar-db:
        condition: service_healthy
    networks:
      - microservices-network

  sonar-db:
    image: postgres:16-alpine
    # sin ports: solo lo alcanza SonarQube por la red interna
    networks:
      - microservices-network
```

`casc.yaml` se monta en `/var/jenkins_home/casc_configs`. Cada job usa `cpsScm` con
`url('file:///workspace')`, rama `*/main` y `scriptPath` `services/empleados-service/Jenkinsfile`
o `services/vacaciones-service/Jenkinsfile`.

El stage de verificación del Punto 1 (job `verificar-docker`, también en JCasC) hace
`docker --version` y `docker ps`. Si ambos corren, el socket y el grupo quedaron bien.

## Fases

La Fase 1 va primero: sin Jenkins no hay pipeline. Las Fases 2 y 3 comparten el Jenkinsfile
de empleados; no se edita en dos PRs a la vez. El Jenkinsfile de vacaciones puede escribirse
en paralelo con la Fase 3 en cuanto la Fase 1 dejó el agente Node y el `casc.yaml`. La Fase 4
junta los dos archivos en el stage E2E y depende de que las features 03 y 04 del Reto 6
existan si se quiere cerrar el criterio con el escenario de offboarding.

### Fase 0 — Plan y STATUS

- Este archivo y [`STATUS.md`](STATUS.md).
- `CLAUDE.md` apunta al Reto 7. El Reto 6 queda como referencia, con las fases 7 y 8 abiertas.

No depende de nada.

### Fase 1 — Jenkins en el compose — criterio 1 (1.0)

Carpeta nueva `jenkins/` y el servicio en `docker-compose.yml`. Todavía no hay SonarQube.

- Dockerfile sobre `jenkins/jenkins:lts-jdk21`, `jenkins-plugin-cli` con la lista de arriba,
  `docker.io`, entrypoint del GID del socket.
- `name: microservicios` en el compose. Puerto de host **9090**. Volúmenes `jenkins_data`,
  `jenkins_m2`, `jenkins_npm`. Bind `./:/workspace`.
- JCasC: usuario admin desde el entorno, job `verificar-docker` con el pipeline de
  `docker --version` y `docker ps`.
- `.env.example`: `JENKINS_ADMIN_ID`, `JENKINS_ADMIN_PASSWORD`.
- Comprobar en el navegador `http://localhost:9090`: login con esas variables, plugins ya
  instalados (no aparece el asistente ni «hay plugins pendientes»), el job de verificación
  en verde.

Depende de la Fase 0. Docker Desktop necesita el socket compartido (viene activo por defecto).

### Fase 2 — Jenkinsfile de empleados: build, test y reportes — criterio 2 (1.0)

Solo `services/empleados-service/Jenkinsfile` y el job en `jenkins/casc.yaml`.

- Pipeline declarativo. Stages `Checkout` (imprime `git rev-parse HEAD` del checkout), `Build`
  (`mvn clean compile -DskipTests`), `Test` (`mvn verify` con `failure.ignore` y las variables
  de Testcontainers).
- `post { always { } }` del stage Test: `junit` de Surefire y Failsafe, `recordCoverage` JaCoCo
  con `sourceCodeRetention: 'EVERY_BUILD'`.
- `skipStagesAfterUnstable()`. Agente `maven:3.9-eclipse-temurin-21`, `reuseNode true`, socket,
  red `DOCKER_NETWORK`, caché `/root/.m2`.
- El job aparece al levantar Jenkins, sin crearlo en la UI.
- Segunda ejecución: la gráfica de tendencia de tests y la de cobertura se ven en el build.
- Romper a propósito una unitaria, correr, y ver en la pestaña **Test Result** el nombre de la
  prueba y el mensaje, sin abrir el log. Revertir. Guardar la nota para `EVIDENCIAS.md`.

Depende de la Fase 1. `mvn verify` exige que el daemon acepte el socket desde el agente.

### Fase 3 — SonarQube y Quality Gate — criterio 3 (1.0)

`sonarqube`, `sonar-db`, `sonar-bootstrap` en el compose, y dos stages nuevos en el Jenkinsfile
de empleados.

- Sonar accesible en `http://localhost:9000`. Healthcheck con `start_period` largo (el primer
  arranque pasa de un minuto). Memoria de Docker Desktop: dejar al menos 4 GB al engine; si
  Sonar reinicia en bucle, el síntoma es Elasticsearch sin mapa de memoria.
- `jenkins/init-sonar.sh` deja el gate `Reto7` (≥ 70 % total y en código nuevo), los proyectos
  `empleados-service` y `vacaciones-service`, el webhook y el token en el volumen.
- JCasC registra la instalación `sonarqube`.
- Stage `SonarQube` y stage `Quality Gate` como en la tabla de decisiones. El token se lee de
  `/run/sonar-bootstrap/token` en el controlador y se pasa al agente por entorno, sin imprimirlo
  en el log (`set +x`).
- `sonar.coverage.exclusions` alineadas con JaCoCo. `sonar.java.binaries=target/classes`.
- Dashboard con bugs, vulnerabilidades, code smells, cobertura, duplicación y el gate en
  Passed.
- Bajar la cobertura por debajo del 70 % (quitar tests de forma temporal) y ver el pipeline
  detenerse en **Quality Gate**. Revertir.

Depende de las Fases 1 y 2: el XML de JaCoCo tiene que existir antes del scanner.

### Fase 4 — Imagen, E2E y segundo Jenkinsfile — criterio 4 (1.0)

Los dos Jenkinsfiles, `jenkins/wait-for-healthy.sh`, `sonar-project.properties` de vacaciones.

Antes de dar esta fase por cerrada, las fases 7 y 8 del Reto 6 tienen que haber dejado
`03-vacaciones.feature` y `04-offboarding.feature`. Si se adelanta el pipeline, la etapa E2E
se escribe igual y se corre contra 00–02; el criterio del escenario en español de offboarding
no se marca ✅ hasta que esas features existan.

- Stage `Package`: `docker build` vía el plugin Docker (el daemon ve las rutas del host) con
  tags `micro/empleados-service:<BUILD_NUMBER>` y `:latest`. Comprobar que `docker image inspect`
  ve la imagen. El stage corre en `agent any` (el controlador ya tiene el cliente y el socket).
- Stage `E2E Tests`, dentro de `lock('ecosistema-e2e')`:
  1. `docker compose -p micro-ci up -d --build` con `GATEWAY_PORT=18080` y el resto de puertos
     de la tabla.
  2. `jenkins/wait-for-healthy.sh`.
  3. En `e2e-tests/`, `npm test` con `BASE_URL=http://host.docker.internal:18080` y las
     credenciales de la semilla. `|| true`.
  4. `post { always }`: `junit`, `cucumber`, `publishHTML`, `docker compose logs` archivado,
     `docker compose -p micro-ci down -v`.
- El mismo esqueleto en `services/vacaciones-service/Jenkinsfile`: Checkout, Build
  (`npm ci`), Test (`npm test`), Sonar (`sonar-scanner` con el lcov), Quality Gate, Package
  (`micro/vacaciones-service`), E2E. El E2E es la misma suite; el lock serializa las dos corridas.
- En la UI, el reporte Cucumber muestra características y escenarios en español sin abrir el
  log de consola.
- Los dos pipelines, de punta a punta, en verde una vez.

Depende de las Fases 1 a 3 y, para el escenario de retiro, de la Fase 7 del Reto 6.

### Fase 5 — Tres corridas, fallos simulados y README — criterio 5 (1.0)

- Cada pipeline, tres veces seguidas, sobre el mismo `jenkins_data` (sin borrar el volumen de
  Jenkins). Si un build es intermitente, la causa se anota en `STATUS.md` y se corrige: casi
  siempre es la espera de salud, el lock o un `down -v` que no corrió.
- Cuatro fallos, uno por uno, revertidos después de capturar la pantalla:
  1. Una unitaria rota → el pipeline se queda en **Test**, amarillo, y Test Result nombra la prueba.
  2. Cobertura bajo 70 % → se detiene en **Quality Gate**.
  3. Un `RUN` inválido en el Dockerfile → se detiene en **Package**, rojo.
  4. Un escenario BDD con el código esperado cambiado → se detiene en **E2E Tests** y el
     reporte Cucumber nombra el escenario en español.
- README raíz, sección Reto 7: qué es CI en este monorepo, URL y usuario de Jenkins (y que no
  hay contraseña inicial: la pone JCasC), cómo levantar, cómo se crean los jobs, cómo lanzar
  un pipeline a mano, qué hace cada stage, la tabla de reportes de abajo, y la diferencia
  entre verde, amarillo (`UNSTABLE`) y rojo (`FAILURE`).
- Capturas pedidas por el PDF, en `docs/reto7/EVIDENCIAS.md`: pipeline en verde, Test Result,
  Coverage, reporte BDD en español, y un build fallido con la prueba o el escenario identificado.
- (Opcional) disparo al hacer push: `pollSCM('H/5 * * * *')` en los Jenkinsfiles, o el plugin
  Generic Webhook Trigger contra GitHub. No bloquea el criterio.

Depende de la Fase 4.

## Tabla de reportes que el README tiene que copiar

| Stage | Archivo | Paso de Jenkins | Dónde se mira |
|---|---|---|---|
| Test (Java) | `target/surefire-reports/*.xml`, `target/failsafe-reports/*.xml` | `junit` | Pestaña Test Result, tendencia entre builds |
| Test (Java) | `target/site/jacoco/jacoco.xml` | `recordCoverage` parser `JACOCO` | Pestaña Coverage |
| Test (Node) | `reports/junit.xml` | `junit` | Test Result |
| Test (Node) | `reports/lcov.info` | `recordCoverage` parser `LCOV` | Coverage |
| SonarQube | el mismo XML / lcov | `mvn sonar:sonar` o `sonar-scanner` | `http://localhost:9000`, proyecto y Quality Gate |
| E2E | `e2e-tests/reports/junit.xml` | `junit` | Test Result (escenarios) |
| E2E | `e2e-tests/reports/cucumber.json` | `cucumber` | Pestaña Cucumber Reports, en español |
| E2E | `e2e-tests/reports/cucumber.html` | `publishHTML` | Enlace «Reporte BDD» |
| E2E | `docker-logs.txt` | `archiveArtifacts` | Artefactos del build |

## Criterios de evaluación ↔ fases

| # | Elemento | Pts | Fase |
|---|---|---|---|
| 1 | Jenkins en Docker, Dockerfile con plugins de reportes, socket, pipeline de verificación, sin pasos manuales de plugins | 1.0 | 1 |
| 2 | Jenkinsfile declarativo, Checkout / Build / Test, `junit` + `recordCoverage` en `post { always }`, jobs como código, tendencia | 1.0 | 2 |
| 3 | SonarQube en el compose, cobertura del Reto 6 importada, Quality Gate ≥ 70 %, el pipeline falla si el gate no pasa | 1.0 | 3 |
| 4 | Package, E2E con la BDD, reporte Cucumber en español, logs archivados, dos Jenkinsfiles, `down -v` del proyecto de CI | 1.0 | 4 |
| 5 | Tres corridas, cuatro fallos en la etapa correcta y visibles en el reporte, README con tabla y capturas | 1.0 | 5 |

## Fuera de alcance de Reto 7 (a propósito)

- CD: desplegar a otro entorno, Kubernetes, o promover la imagen más allá del daemon local.
- Registry local del `:5000` y Docker Hub.
- Jenkinsfiles de `auth-service`, `notificaciones-service`, `perfiles-service`,
  `departamentos-service` y `api-gateway`. El de auth es el candidato si sobra tiempo.
- Reescribir pruebas o cambiar los comandos del Reto 6. El pipeline solo los invoca.
- Cerrar las fases 7 y 8 del Reto 6 dentro de este plan. Son prerrequisito de la evidencia de
  offboarding, y se cierran en `docs/reto6/`.
- Correr la BDD en cada push a una rama de trabajo cuando el equipo active el webhook opcional
  y el tiempo de E2E resulte demasiado alto. Mientras el criterio pida «siempre», se ejecuta
  siempre.
- Instalar Maven, Node o Python dentro de la imagen de Jenkins.
- Tocar contratos HTTP, eventos o el esquema de las bases de negocio.
