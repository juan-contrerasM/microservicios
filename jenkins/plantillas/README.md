# Plantilla de pipeline (Fase 9, paquete 9.TPL)

`Jenkinsfile.plantilla` es el pipeline común de los siete servicios (estándar E18/E19). Cada
servicio lo instancia copiándolo a `services/<servicio>/Jenkinsfile` y cambiando solo dos líneas:

```groovy
def SERVICIO = 'auth-service'   // carpeta bajo services/
def STACK = 'python'            // java | go | node | python
```

| Etapa | Qué hace | Detiene el pipeline si… |
|---|---|---|
| Checkout | `checkout scm` y commit | — |
| Build / Lint / Unit / Integración | En un contenedor con la imagen del stack (misma versión que el Dockerfile) y caché en volúmenes `jenkins_*` | Compila mal, el lint falla, una prueba falla o la cobertura < 70 % |
| Reportes | `junit` + `recordCoverage` (JaCoCo, Cobertura o Go) | — |
| SonarQube + Quality Gate | `waitForQualityGate abortPipeline: true` | El Quality Gate (cobertura ≥ 70 %) no pasa |
| Seguridad | Dependencias (`dependency-check`, `govulncheck`, `npm audit`, `pip-audit`) y secretos (Gitleaks), en paralelo | Hallazgo crítico |
| Package | Imagen `localhost:5001/<servicio>:<commit>-<build>` + escaneo Trivy | Vulnerabilidad CRITICAL corregible |
| Push Registry | Publica tag inmutable y `latest`; archiva el digest | — |
| Deploy + smoke | Bajo `lock('ecosistema-e2e')`; lo completa la Fase 10 | — |

Validación de sintaxis contra el Jenkins del Compose (`http://localhost:8090`):

```bash
curl -u admin:<clave> -H "<crumb>" -X POST \
  -F "jenkinsfile=<jenkins/plantillas/Jenkinsfile.plantilla" \
  http://localhost:8090/pipeline-model-converter/validate
# → Jenkinsfile successfully validated.
```

Los siete Jenkinsfile, los proyectos SonarQube, los jobs por JCasC y los fallos simulados se
completan en la Fase 9 (Ola 3); esta plantilla es el contrato que esa fase aplica.
