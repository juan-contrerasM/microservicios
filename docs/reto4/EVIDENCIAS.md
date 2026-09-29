# Evidencias — Reto 4

Corrida del **29 de septiembre de 2026** contra `http://127.0.0.1:8080`. `localhost` en esta
máquina se queda colgado; el gateway es el mismo. Las bases ya tenían datos de las pruebas de
las etapas 2 a 5, así que no se vaciaron los volúmenes y no se repitió el `POST` de `E001`.

## 1. Fan-out del alta y baja lógica

`E001` quedó `RETIRADO` con `fechaRetiro` `2026-09-29T15:09:40.885352Z`. El filtro
`GET /empleados?estado=RETIRADO` lo incluye.

`GET /perfiles/E001` respondió 200, `archivado: true`, nombre `Juan Carlos` (el `PUT` lo
sincronizó) y el teléfono `3001234567` que se editó por REST.

`GET /notificaciones/E001` respondió 200 con dos filas, no más:

- `BIENVENIDA`, `Bienvenido Juan Pérez`
- `DESVINCULACION`, `Su cuenta ha sido desvinculada`

## 2. Validaciones de vacaciones

Sobre `E950`, antes de retirarlo:

| Caso | Resultado |
|---|---|
| 15 al 30 de marzo de 2027 | 201, `V-2027-0001`, `PROGRAMADA`, `diasHabiles` 12 |
| `fechaFin` anterior a `fechaInicio` | 400, `La fechaFin debe ser posterior a la fechaInicio` |
| `fechaInicio` en 2026-01-10 | 400, `La fechaInicio no puede ser anterior a la fecha actual` |
| Rango que cruza marzo de 2027 | 400, mensaje de solapamiento y `periodoEnConflicto` `V-2027-0001` |
| Empleado `NO-EXISTE` | 400, `El empleado con id NO-EXISTE no existe` |
| `DELETE /vacaciones/V-2027-0001` | 200, `CANCELADA` |
| El mismo rango otra vez | 201, `V-2027-0002` |
| `GET /vacaciones/V-NO-EXISTE` | 404, `El período de vacaciones con id V-NO-EXISTE no existe` |
| Período nuevo después del retiro | 400, `El empleado con id E950 está retirado` |

Notificaciones registró, una vez por período creado:

`[NOTIFICACIÓN] Tipo: VACACIONES | Para: ana.lopez@empresa.com | Mensaje: "Sus vacaciones del 2027-03-15 al 2027-03-30 han sido programadas"`

## 3. Deduplicación

El mismo envelope (`id` `11111111-1111-1111-1111-111111111111`, routing key `empleado.creado`)
se publicó dos veces en `onboarding.eventos` por la API de administración, que es el mismo
`basic.publish` de la UI. Las dos respuestas fueron `{"routed": true}`.

Después:

- `GET /notificaciones/E002` trajo una sola fila `BIENVENIDA`, mensaje `Bienvenido Ana Gómez`.
- `GET /perfiles/E002` trajo un solo objeto, `empleadoId` `E002`, email `ana.gomez@empresa.com`, `archivado` false.
- El log del contenedor escribió una sola línea `[NOTIFICACIÓN]` para `ana.gomez@empresa.com`.

Para repetirlo desde la UI: Exchange `onboarding.eventos`, Publish message, routing key
`empleado.creado`, el JSON de la carpeta 8 de la colección, dos veces sin cambiar el `id`.

## 4. Puertos de negocio no publicados

`curl` a `127.0.0.1` en `8081`, `8082`, `8083`, `8084` y `8085` terminó con conexión rechazada
(curl exit 7). `8080` y `15672` sí respondieron.

Dentro de la red, OpenAPI respondió:

| Servicio | Ruta | Código |
|---|---|---|
| empleados | `/swagger-ui.html` | 302 |
| departamentos | `/swagger/index.html` | 200 |
| perfiles | `/swagger-ui.html` | 302 |
| notificaciones | `/docs` | 200 |
| vacaciones | `/openapi.json` | 200 |

El 302 de Springdoc redirige a la UI. No se publicaron esos puertos para abrirla desde el host.

## 5. Persistencia

`docker compose down` y `docker compose up -d`, sin `-v`. Al volver a `healthy`:

- `GET /perfiles/E002` 200, el mismo perfil (`d8eab8f7-11f4-4af7-b53e-d53944339d94`).
- `GET /notificaciones/E002` 200, la misma `BIENVENIDA` (`64579875-e6fd-4977-acc2-25274dc86644`).
- `GET /vacaciones/V-2027-0002` 200, sigue `PROGRAMADA`.
- `GET /empleados/E001` 200, sigue `RETIRADO` con la misma `fechaRetiro`.
