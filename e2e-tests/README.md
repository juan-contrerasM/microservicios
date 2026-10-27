# Pruebas BDD (`e2e-tests`)

La punta de la pirámide del Reto 6. No es un microservicio: no tiene Dockerfile ni base de datos. Habla con el sistema **solo** por `BASE_URL` (el API Gateway).

## Por qué Cucumber.js

El enunciado pide escenarios en Gherkin, un contexto por escenario y tres reportes (HTML, JSON y JUnit). Cucumber.js cumple los tres con formateadores propios y acepta `# language: es`. Behave no genera HTML ni un JSON del todo compatible con Cucumber, y Cucumber-JVM abriría otro proyecto Maven. Node 22 ya es el lenguaje del Gateway y de vacaciones. El cliente es `fetch`: no añade axios y no lanza en un 4xx, que aquí es un resultado esperado.

## Cómo correrlas

El sistema tiene que estar arriba (`docker compose up` en la raíz). Node 22.

```bash
cd e2e-tests
cp .env.example .env   # en Windows: copy .env.example .env
npm ci
npm test
```

`npm test` es un solo comando. Escribe:

| Archivo | Qué es |
|---|---|
| `reports/junit.xml` | JUnit, para Jenkins (Reto 7) |
| `reports/cucumber.json` | JSON de Cucumber |
| `reports/cucumber.html` | Informe HTML |

`reports/` está en el `.gitignore` de la raíz. Si un escenario falla, el proceso sale con código distinto de cero.

`npm run test:unit` ejercita el polling, la extracción del token y los ids sin levantar el sistema. `npm run calibrar` mide diez propagaciones reales (ver abajo).

## World

Cucumber crea un `World` nuevo por escenario (`support/world.js`). Ahí vive `baseUrl`, el token actual, `ultimaRespuesta` (status y cuerpo) y lo creado en el escenario (empleado, departamento, token de activación). Un paso `Dado que estoy autenticado como …` hace login y guarda el token; los `Cuando` lo leen de ahí. Nada se filtra al escenario siguiente.

## Variables

Ninguna credencial está en el código. Se leen de `e2e-tests/.env` (plantilla en `.env.example`):

| Variable | Uso |
|---|---|
| `BASE_URL` | Gateway. Default `http://localhost:8080` |
| `ADMIN_USUARIO` / `ADMIN_PASSWORD` | Semilla del compose (`AUTH_ADMIN_*`) |
| `USER_PASSWORD` | Clave que la suite fija en cada empleado. Política del Reto 5: 8 caracteres, mayúscula, minúscula y dígito |
| `POLL_INTENTOS` / `POLL_INTERVALO_MS` | Presupuesto del polling. Default 15 × 2000 ms |

## Datos y polling

Cada escenario genera ids nuevos (`Date.now()` + aleatorio). No se borra nada: un `DELETE /empleados` es un retiro con eventos. La misma suite puede correr varias veces sobre el mismo volumen.

`eventualmente()` repite una condición. No hay `sleep` fijo en los pasos. Si el presupuesto se agota, el fallo dice que **el evento no se procesó**, no que «el login falló».

El token de activación se lee de `GET /notificaciones/{empleadoId}` (como ADMIN) y se extrae `token=` del mensaje `SEGURIDAD`. Es la misma vía que la colección del Reto 5 y pasa por `BASE_URL`.

`empleadoConCredenciales()` (en `support/fabrica.js`) deja un USER usable para las fases siguientes: crea departamento y empleado, espera el token, hace `reset-password` y login. Lo usan los antecedentes de seguridad. No cambia el token del escenario; el paso de autenticación decide el rol.

## Calibración

Cadena medida: `POST /empleados` (201) → `empleado.creado` → auth publica `usuario.creado` → notificación `SEGURIDAD` con token. Diez corridas seguidas, el 14 de octubre de 2026, con sondeo de 200 ms solo en el script de medición:

| min | p50 | p95 | max | media |
|---|---|---|---|---|
| 259 ms | 286 ms | 332 ms | 332 ms | 294 ms |

La suite **no** baja ese intervalo. Deja `POLL_INTENTOS=15` y `POLL_INTERVALO_MS=2000` (30 s, los valores del enunciado). El máximo observado cabe unas 90 veces en ese presupuesto: alcanza para un broker frío o una máquina más lenta, y el caso feliz no espera 30 s. El primer intento es inmediato; si el evento ya está, no hay espera. Si el primer vistazo se adelanta, el siguiente es a los 2 s.

## Qué afirma cada feature

- `00-humo.feature`: `GET /health` del Gateway es 200.
- `01-seguridad.feature`: sin token, token malformado y firma alterada → 401 `No autenticado`. Un `USER` lista empleados (200) y no puede crearlos ni retirarlos (403). Un `ADMIN` crea y consulta (201 / 200). Un `USER` edita su perfil (200) y el de otro (403). Antecedentes: un empleado con credenciales y un departamento de prueba.
- `02-onboarding.feature`: el alta publica, eventualmente, una `SEGURIDAD` con token; `reset-password` responde 200 `Contraseña actualizada`; el login queda en 200 con `role` `USER`.
- `03-vacaciones.feature`: programación futura (201 y aviso `VACACIONES`), rango invertido (400), solapamiento (400) y ciclo completo `PROGRAMADA → EN_CURSO → FINALIZADA`. Los endpoints `forzar-inicio` y `forzar-fin` controlan el tiempo sin esperar al cron; el login se bloquea y se recupera mediante polling sobre hechos observables.
- `04-offboarding.feature`: retiro lógico, aviso `CUENTA`, login bloqueado, auditoría por `estado=RETIRADO` y recuperación que conserva la respuesta genérica pero rechaza el reset de una cuenta desactivada permanentemente.

Antes de activar, el login con la clave que todavía no se fijó responde **401 `Credenciales inválidas`**. La cuenta nace con `password_hash` nulo y auth rechaza la clave antes de mirar el estado, para no revelar si el correo existe. `La cuenta no está activa` aparece cuando la clave sí coincide y la cuenta no está `ACTIVA` (vacaciones y offboarding). La notificación `SEGURIDAD` es la prueba de que la cuenta se creó y quedó pendiente.

Los rechazos del alta se confirmaron contra el servicio, no contra un 409 supuesto:

| Caso | Código | El cuerpo contiene |
|---|---|---|
| Departamento inexistente | 400 | `no existe` |
| Campo obligatorio ausente | 400 | `obligatorio` |
| Email mal formado | 400 | `formato válido` |
| Email duplicado | 400 | `ese email` |

El duplicado es 400 porque `ConflictException` de empleados se traduce a 400 desde el Reto 1 (`Ya existe un empleado registrado con ese email`). El departamento inexistente también es 400 (`El departamento con id … no existe`), no 404.

## Reproducibilidad comprobada

El 15 de octubre de 2026 la suite completa pasó tres veces seguidas, sin reiniciar ni limpiar
volúmenes: 18 escenarios y 137 pasos en cada ejecución. Los tiempos fueron 52,448 s, 49,415 s y
46,673 s. Después se cambió temporalmente la expectativa del humo de 200 a 201: Cucumber salió
con código 1, mostró `esperado 201, obtenido 200`, JUnit registró un fallo y el HTML marcó el
escenario fallido. La expectativa se restauró y la ejecución final volvió a quedar verde.

La evidencia completa está en [`../docs/reto6/EVIDENCIAS.md`](../docs/reto6/EVIDENCIAS.md).
