# Evidencias — Reto 7

Lo que ya se vio en marcha, y lo que falta para cerrar el criterio 5. La fase 2 queda
respaldada por la API de Test Result y por la gráfica de tests. Las capturas de pantalla
de la interfaz siguen pendientes para la fase 5.

## Fase 1 — job `verificar-docker`, build 3, SUCCESS

Consola del 26 oct 2026, usuario `admin`, [http://localhost:9090](http://localhost:9090):

```
Jenkins está correctamente configurado
+ docker --version
Docker version 27.5.1, build 9f9e405
+ docker compose version
Docker Compose version v2.32.4
+ docker ps
... jenkins, sonarqube (healthy), sonar-db (healthy) ...
Finished: SUCCESS
```

El build 1 falló porque el script inline estaba fuera del sandbox y Jenkins exigía
aprobarlo a mano. El build 2 llegó a la etapa y `docker` no estaba en el `PATH` (Debian 13
instala `dockerd` con `docker.io` y no el cliente). El build 3 es el que cierra la fase.

## Fase 3 — Quality Gate `Reto7`

`GET /api/qualitygates/show?name=Reto7` después de `sonar-bootstrap`:

| Condición | Operador | Umbral |
|---|---|---|
| `coverage` | LT | 70 |
| `new_coverage` | LT | 70 |

Es el gate por defecto. Sonar, al crear el gate, agrega condiciones de «clean as you code»
(cobertura nueva al 80 %, violaciones, hotspots, duplicación). El bootstrap las quita y deja
las dos del plan. El token queda en el volumen `sonar_bootstrap`, no en Git.

## Fase 2 — Test Result, cobertura y una unitaria rota

Job `empleados-service`, checkout `file:///workspace` rama `main`. El build 1 no encontró el
Jenkinsfile; a partir del build 2 el pipeline publica Surefire, Failsafe y JaCoCo.

| Build | Test Result | Cobertura de línea | Instrucciones | Resultado del job |
|---|---|---|---|---|
| 2 | 79 passed, 0 failed | 88,07 % | 88,41 % | FAILURE más adelante (el gate aún no autenticaba) |
| 3 | 79 passed, 0 failed | 88,07 % | 88,41 % | FAILURE más adelante |
| 4 | 79 passed, 0 failed | 88,07 % | 88,41 % | ABORTED en el Quality Gate |
| 5 | 79 passed, 0 failed | 88,07 % | 88,41 % | siguió de largo; la etapa Test ya había publicado |
| 6 | 78 passed, 1 failed | 88,07 % | 88,41 % | UNSTABLE |

La segunda ejecución (build 3) ya tiene la cobertura del build 2. La gráfica de tests está en
`/job/empleados-service/test/trend` (responde `image/png`).

Build 6, pestaña Test Result, sin abrir la consola:

- Clase: `com.microservicios.Reto1.controller.HealthControllerTest`
- Prueba: `healthDevuelve200CuandoPostgreSqlEstaDisponible`
- Estado: FAILED
- Mensaje: `[Fallo deliberado de la fase 2] expected: 418 I_AM_A_TEAPOT but was: 200 OK`

Ese fallo marcó el build UNSTABLE y `skipStagesAfterUnstable()` no entró a SonarQube, Quality
Gate, Package ni E2E. La aserción se revirtió; `main` vuelve a esperar `HttpStatus.OK`.
