# SonarQube

URL: http://localhost:9000 · usuario `admin` · contraseña en `secrets/sonar_admin_password.txt`.

`sonar-bootstrap` (contenedor de un solo uso) deja preparado al arrancar: la contraseña de admin, el
Quality Gate `Reto7` (cobertura total y de código nuevo ≥ 70 %) como gate por defecto, los siete
proyectos, el webhook hacia Jenkins y un token que Jenkins lee de un volumen compartido.

| Servicio | Configuración | Cobertura importada |
|---|---|---|
| empleados, perfiles | propiedades `sonar.*` del `pom.xml` | JaCoCo combinado (unitarias + IT) |
| departamentos | `sonar-project.properties` | `coverage.out` |
| auth, notificaciones | `sonar-project.properties` | `reports/coverage.xml` |
| api-gateway, vacaciones | `sonar-project.properties` | `reports/lcov.info` |

La etapa «Quality Gate» del pipeline espera el resultado (`waitForQualityGate abortPipeline: true`):
si la cobertura baja de 70 %, el build se detiene antes de empaquetar.
