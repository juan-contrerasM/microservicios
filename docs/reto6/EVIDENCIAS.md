# Evidencias — Reto 6

Validación realizada el **15 de octubre de 2026** sobre Windows, Docker Desktop 29.8.0,
Node.js 22, JDK 21 y Python 3.12. Los reportes generados están ignorados por Git para evitar
versionar artefactos; se regeneran con los comandos indicados.

## Pruebas por servicio

| Suite | Resultado | Cobertura / evidencia |
|---|---|---|
| `services/vacaciones-service`: `npm test` | 63/63 pruebas | 100 % de líneas en los cuatro módulos de dominio medidos y 95,1 % de ramas. JUnit y lcov en `reports/`. |
| `services/empleados-service`: `./mvnw clean verify` | 74 unitarias + 5 integraciones; `BUILD SUCCESS` | 88,1 % de líneas (289/328) en las clases medidas. PostgreSQL 16 real mediante Testcontainers y esquema aplicado con Liquibase. |
| `services/auth-service`: `python -m pytest` | 31/31 pruebas | 70 % total; `casos.py` 89 % y `seguridad.py` 97 %. JUnit y cobertura XML/HTML en `reports/`. |
| `e2e-tests`: `npm run test:unit` | 9/9 pruebas | Entorno, polling, tokens, ids únicos y fechas UTC relativas. |

Las reglas rotas deliberadamente durante las fases unitarias fueron detectadas: inversión del
solapamiento de vacaciones, omisión del duplicado por email en empleados y reactivación de una
cuenta retirada en auth. Cada mutación produjo pruebas fallidas y se revirtió.

## Suite BDD completa

Se ejecutó `npm test` tres veces seguidas contra el mismo despliegue y los mismos volúmenes. No
hubo limpieza entre corridas; cada escenario fabrica ids nuevos.

| Corrida | Escenarios | Pasos | Duración | Resultado |
|---|---:|---:|---:|---|
| 1 | 18/18 | 137/137 | 52,448 s | verde |
| 2 | 18/18 | 137/137 | 49,415 s | verde |
| 3 | 18/18 | 137/137 | 46,673 s | verde |

Los escenarios añadidos en la fase 7 comprueban:

- vacaciones futuras y notificación `VACACIONES`;
- rechazo de rango invertido y período solapado;
- `PROGRAMADA → EN_CURSO → FINALIZADA`, suspensión temporal del login y reactivación;
- retiro lógico, aviso `CUENTA`, login bloqueado y presencia en la auditoría `RETIRADO`;
- respuesta genérica de recuperación y rechazo exacto del reset para una cuenta retirada.

Los hechos asíncronos se esperan con polling de 15 × 2 s. Los pasos no usan `sleep`; terminan en
cuanto aparece la notificación o cambia el resultado del login. Las vacaciones usan mañana → +3
días y los endpoints ADMIN `forzar-*`, por lo que no dependen del reloj del cron.

## Fallo controlado y reportes

Se cambió temporalmente el código esperado del escenario de humo de 200 a 201. La suite mostró:

```text
AssertionError: esperado 201, obtenido 200
18 scenarios (1 failed, 17 passed)
137 steps (1 failed, 136 passed)
EXIT_CODE=1
```

Además, `reports/junit.xml` registró `tests="18" failures="1"` y
`reports/cucumber.html` incluyó el escenario fallido. Después se restauró la expectativa 200 y
se ejecutó nuevamente la suite completa para dejar los tres reportes en estado verde.

## Reproducción

```bash
cp .env.example .env
docker compose up --build -d
docker compose ps
cd e2e-tests
cp .env.example .env
npm ci
npm test
```

En PowerShell, `Copy-Item` sustituye a `cp`. El comando BDD genera simultáneamente:

- `e2e-tests/reports/cucumber.html`;
- `e2e-tests/reports/cucumber.json`;
- `e2e-tests/reports/junit.xml`.

El contenedor opcional `bdd-tests` no se añadió: el enunciado lo presenta como opcional y el
runner local ya entrega un único comando reproducible, las variables externas y los tres
formatos obligatorios. RabbitMQ quedó con `restart: on-failure`; se verificó su arranque saludable
sobre un volumen Compose nuevo y aislado. Jenkins queda fuera del alcance de este reto.
