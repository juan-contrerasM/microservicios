# Evidencias — Reto 7

Lo que ya se vio en marcha, y lo que falta para cerrar el criterio 5. Las capturas de la
interfaz (pipeline verde, Test Result, Coverage, BDD en español y el fallo identificado)
entran cuando los Jenkinsfiles estén en `main` y los dos pipelines hayan corrido.

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

## Bloqueo para las fases 2, 4 y 5

El job `empleados-service` hace checkout de `file:///workspace`, rama `main`, y el build 1
termina así:

```
ERROR: .../services/empleados-service/Jenkinsfile not found
Commit message: "feat(reto6): pruebas BDD de humo, seguridad y onboarding (fases 4-6)"
Finished: FAILURE
```

El Jenkinsfile está en el working tree. `main` no lo contiene, y el plan fija el checkout en
esa rama. Hasta que esos archivos entren en `main`, no hay Test Result, ni Coverage, ni el
reporte BDD, ni las cuatro capturas de fallo.
