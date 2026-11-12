# Jenkins

URL: http://localhost:8090 · usuario `JENKINS_ADMIN_ID` (por defecto `admin`) · contraseña en
`secrets/jenkins_admin_password.txt`.

## Jobs

Jenkins se configura solo con JCasC (`jenkins/casc.yaml`): al arrancar crea un job por servicio
(`api-gateway`, `empleados-service`, `departamentos-service`, `auth-service`, `perfiles-service`,
`vacaciones-service`, `notificaciones-service`) más `verificar-docker`. Cada job toma el
`Jenkinsfile` del servicio desde la rama `JOBS_BRANCH` (por defecto `proyecto-final`) del repositorio
montado en `/workspace` y revisa cambios cada 2 minutos (`pollSCM`).

## Etapas

Checkout → Dependencias de integración → Build → Lint → Unit → Integración → SonarQube → Quality
Gate → Seguridad (Trivy de dependencias + Gitleaks) → Package (imagen con tag `<commit>-<build>` y
Trivy de imagen) → Push Registry → E2E (despliegue aislado `micro-ci` + BDD) → Deploy (entorno en
ejecución con smoke test y rollback). Un fallo detiene todas las etapas siguientes.

Los siete `Jenkinsfile` se **generan**: se edita `jenkins/plantillas/Jenkinsfile.plantilla` y se
ejecuta `bash jenkins/plantillas/generar.sh`. Validación de sintaxis sin ejecutar:

```bash
curl -u admin:<clave> -X POST -F "jenkinsfile=<services/auth-service/Jenkinsfile" \
  http://localhost:8090/pipeline-model-converter/validate
```

## Simular fallos (9.FAIL)

«Build with Parameters» → `SIMULAR_FALLO`:

| Valor | Qué inyecta (solo en el workspace del build) | Dónde se detiene |
|---|---|---|
| `unitaria` | Una prueba que falla | Unit |
| `cobertura` | Umbral de cobertura al 99 % | Unit (Integración en Java) |
| `dockerfile` | `RUN exit 1` al final del Dockerfile | Package |
| `bdd` | Un escenario que espera HTTP 418 | E2E |

## Problemas frecuentes

- **Agentes sin red:** Jenkins detecta su red `cicd` al arrancar (`DOCKER_NETWORK=auto`).
- **Bind mounts desde Jenkins:** el daemon no ve el workspace del job; las rutas se traducen con
  `jenkins/host-repo.sh` (ver `jenkins/docker-compose.ci.yml`).
- **Jobs que no aparecen tras recrear Jenkins:** «Manage Jenkins → Configuration as Code → Reload».
- **Quality Gate con `HttpException: Error 401`:** la credencial `sonar-token` de Jenkins no vale en
  el SonarQube de su proyecto. JCasC la lee de `/run/sonar-bootstrap/token` solo al arrancar; por
  eso el volumen `sonar_bootstrap` es por proyecto y Jenkins espera a que `sonar-bootstrap` termine.
  Si aun así aparece (p. ej. tras `docker compose up -d sonar-bootstrap` suelto, que puede rotar el
  token), `docker compose restart jenkins`. En stacks creados antes de este cambio:
  `docker compose up -d --force-recreate sonar-bootstrap jenkins`.
