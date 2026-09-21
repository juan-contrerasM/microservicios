# Evidencias reproducibles — Reto 3

Validación ejecutada el **21 de septiembre de 2026** sobre Docker Engine 29.8.0, desde volúmenes
limpios y mediante la URL pública única `http://localhost:8080`.

## 1. Arranque y punto de entrada único

Comando:

```bash
docker compose --env-file .env.example -p reto3-validation up --build -d
docker compose --env-file .env.example -p reto3-validation ps
```

Resultado: los cinco contenedores quedaron `healthy`:

```text
api-gateway              Up (healthy)   0.0.0.0:8080->8080/tcp
database-departamentos   Up (healthy)   3306/tcp
database-empleados       Up (healthy)   5432/tcp
departamentos-service    Up (healthy)   8081/tcp
empleados-service        Up (healthy)   8080/tcp
```

El Gateway respondió correctamente:

```text
GET http://localhost:8080/health
200 {"status":"UP","service":"api-gateway"}

GET http://localhost:8080/departamentos
200 []
```

Los puertos directos no fueron accesibles desde el host:

```text
GET http://localhost:8081/departamentos  → sin conexión HTTP
GET http://localhost:8082/empleados      → sin conexión HTTP
```

Con `departamentos-service` detenido, el borde permaneció saludable y respondió:

```json
{
  "status": 503,
  "mensaje": "El servicio de departamentos no está disponible",
  "servicio": "departamentos-service"
}
```

## 2. Circuit Breaker: salto de latencia `CLOSED → OPEN`

Preparación:

```bash
docker compose stop departamentos-service
```

Ocho altas consecutivas, con identificadores, emails y números de empleado únicos:

| Petición | HTTP | Tiempo medido | Estado | Estado del circuito |
|---:|---:|---:|---|---|
| 1 | 201 | 11.155s | `PENDIENTE_VALIDACION` | `CLOSED` |
| 2 | 201 | 9.645s | `PENDIENTE_VALIDACION` | `CLOSED` |
| 3 | 201 | 9.704s | `PENDIENTE_VALIDACION` | pasa a `OPEN` |
| 4 | 201 | 39ms | `PENDIENTE_VALIDACION` | `OPEN` |
| 5 | 201 | 36ms | `PENDIENTE_VALIDACION` | `OPEN` |
| 6 | 201 | 33ms | `PENDIENTE_VALIDACION` | `OPEN` |
| 7 | 201 | 38ms | `PENDIENTE_VALIDACION` | `OPEN` |
| 8 | 201 | 36ms | `PENDIENTE_VALIDACION` | `OPEN` |

Consulta observable al terminar:

```json
{"name":"departamentos","state":"OPEN"}
```

El salto de aproximadamente 10 segundos a menos de 40 milisegundos demuestra que, una vez
abierto, el circuito ejecuta el fallback sin tocar la red. Los ocho empleados quedaron
persistidos y consultables.

## 3. Recuperación automática y reconciliación

Se restauró únicamente el proveedor:

```bash
docker compose start departamentos-service
```

Sin reiniciar empleados ni el Gateway, después del tiempo configurado el endpoint observable
reportó `HALF_OPEN`. La llamada de prueba usó deliberadamente `departamentoId: NO-EXISTE`:

```text
POST /empleados → 400 en 180ms
GET /empleados/circuit-breaker → {"name":"departamentos","state":"CLOSED"}
```

El `400` solo puede provenir de una consulta real al proveedor; además, la respuesta de negocio
cuenta como éxito de infraestructura y cierra el circuito.

Finalmente:

```text
POST /empleados/reconciliar
200 {"pendientesEvaluados":16,"reconciliados":16}

GET /empleados/RT4
200 ... "estado":"ACTIVO"
```

No se asignó ningún departamento por defecto y no se borraron empleados silenciosamente.

## 4. Pruebas automatizadas

```text
api-gateway: 10 pruebas, 0 fallos
empleados-service unitarias: 52 pruebas, 0 fallos
empleados-service suite completa con PostgreSQL: 53 pruebas, 0 fallos
docker compose config: válido
```

La colección también se ejecutó desde volúmenes limpios con Newman:

| Flujo | Peticiones | Aserciones | Fallos | Resultado relevante |
|---|---:|---:|---:|---|
| Carpetas 0, 2 y 3: sistema sano | 16 | 34 | 0 | Gateway, propagación de códigos, CRUD y validaciones |
| Carpeta 5: `CLOSED → OPEN` | 10 | 28 | 0 | Las tres primeras altas tardaron 19.6 s, 9.6 s y 7 s; las cinco siguientes, entre 26 y 35 ms |
| Carpeta 6: recuperación | 5 | 12 | 0 | `400` real para departamento inexistente, circuito `CLOSED` y empleado reconciliado como `ACTIVO` |

La primera petición de la carpeta 5 puede tardar más que las siguientes porque incluye el
establecimiento inicial de conexiones. La condición comprobada no depende de un valor exacto:
las llamadas en `CLOSED` esperan los reintentos, mientras que las llamadas en `OPEN` terminan en
decenas de milisegundos sin intentar acceder al proveedor.

La colección ejecutable es [`Reto3.postman_collection.json`](Reto3.postman_collection.json).
Las carpetas 0–3 cubren el flujo sano, la 4 el `503` del Gateway, la 5 el salto de latencia y la
6 la recuperación y reconciliación.

## Reproducción manual

1. Parte de datos limpios: `docker compose down -v`.
2. Levanta: `docker compose up --build -d`.
3. Ejecuta en Postman las carpetas 0–3.
4. Detén departamentos y ejecuta la carpeta 5.
5. Inicia departamentos, espera 35 segundos y ejecuta la carpeta 6.
6. Para el `503` propio del Gateway, detén el backend indicado en la carpeta 4.
