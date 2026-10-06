# Estado de implementación — Reto 5

Fuente de verdad del avance real del equipo. Actualiza este archivo en el mismo commit/PR que
avanza o cierra una etapa. Ver el detalle de cada etapa en
[`PLAN-RETO5.md`](PLAN-RETO5.md). Enunciado: [`reto5.pdf`](reto5.pdf).

El Reto 4 está cerrado; su estado histórico vive en [`docs/reto4/STATUS.md`](../reto4/STATUS.md).
No reabrir esas etapas salvo el `docker-compose.yml` raíz, las rutas del Gateway, los bindings
de `q.notificaciones` y el scheduler de `vacaciones-service`.

Leyenda: ✅ hecho · 🔄 en progreso · ⬜ pendiente

| Etapa | Descripción | Estado | Notas |
|---|---|---|---|
| 0 | Plan, este STATUS y colección Postman en `docs/reto5/` (URL de negocio `http://localhost:8080`) | ✅ | Contrato listo. Importar [`Reto5.postman_collection.json`](Reto5.postman_collection.json). Las carpetas 0 a 10 son automáticas y la 11 comprueba manualmente el puerto cerrado. |
| 1 | `auth-service` (Python 3.12 / FastAPI): semilla ADMIN, bcrypt, `POST /auth/login`, access JWT HS256, OpenAPI | ✅ | Criterio 1, sin la cola en el camino del login. Puerto interno `8086`. Alembic `001_inicial`. La semilla hashea `AUTH_ADMIN_PASSWORD`. |
| 2 | Ciclo de vida: consume `empleado.creado`, `empleado.retirado`, `vacaciones.iniciadas`, `vacaciones.finalizadas`. Publica `usuario.creado`, `usuario.recuperacion`, `cuenta.activada`, `cuenta.desactivada`. Reset, recover y change-password | ✅ | Criterios 1 y 4. Cola `q.auth`, ack manual, prefetch 1. El commit va antes del publish. `vacaciones.finalizadas` no reactiva una `DESACTIVADA_PERMANENTE`. Compose cablea el consumidor y el broker. |
| 3 | Gateway: verifica el JWT, 401 / 403, el USER lee, cambia su clave y edita solo su perfil. Ruta `/auth` | ✅ | Criterios 2 y 3. `JWT_SECRET` y `AUTH_URL` están inyectados; `/auth` funciona por el punto de entrada único. Las rutas de negocio no son públicas. |
| 4 | `notificaciones-service`: `SEGURIDAD` y `CUENTA`. Deja de insertar `BIENVENIDA` y `DESVINCULACION`. Consume inicio y fin de vacaciones | ✅ | Parte del criterio 4. Seis bindings nuevos en `q.notificaciones`. `empleado.creado` solo guarda el destinatario; `empleado.retirado` se confirma sin fila. `usuario.recuperacion` busca el `empleadoId` por email (Alembic `002_destinatario_email` agrega el índice); sin destinatario previo se confirma sin fila. Asuntos SMTP `Seguridad`, `Cuenta`, `Vacaciones iniciadas` y `Vacaciones finalizadas`. 11 tests con pytest. |
| 5 | Scheduler en `vacaciones-service` (`VACACIONES_CRON`) y `POST /vacaciones/{id}/forzar-inicio` y `forzar-fin` | ✅ | Parte del criterio 4. `node-cron` 4 con `noOverlap` y zona UTC; `VACACIONES_CRON` por defecto `* * * * *`, ya inyectada en el compose y en `.env.example`. Cada transición es un `UPDATE ... RETURNING` atómico y publica después del commit. `fechaFin` igual a `fechaInicio` es válido. Los `forzar-*` responden 200 con el período, 400 fuera de estado y 404 si no existe; tag `Desarrollo` en el OpenAPI. README con la limitación de N instancias. 19 tests con `node --test`. |
| 6 | Compose de `auth-service` y `database-auth`, `JWT_SECRET` por entorno, BearerAuth en los OpenAPI, sin publicar `:8086` | ✅ | Criterio 5. Los 15 contenedores arrancan saludables. Auth depende de su Postgres y RabbitMQ, el Gateway depende de auth iniciado, los seis contratos internos declaran `BearerAuth` y `curl :8086` no conecta. |
| 7 | README (token, Gateway vs interceptores, diagrama, cron, N instancias), evidencias del caso borde, Newman | ✅ | Criterio 6. README y diagrama completos. [`EVIDENCIAS.md`](EVIDENCIAS.md) registra 84 requests, 58 scripts y 101 aserciones de Newman, todas sin fallos. |

## Estado de entrega

Las etapas **0** a **7** están cerradas. `auth-service` firma tokens y consume la cola, el
Gateway exige el access JWT en las rutas de negocio, notificaciones registra los avisos
`SEGURIDAD` y `CUENTA`, y vacaciones inicia y finaliza los períodos con el scheduler o con los
`forzar-*`. Auth, su Postgres y el cableado `depends_on` están integrados al compose sin publicar
`:8086`. La ejecución de punta a punta sobre volúmenes limpios terminó con **101/101
aserciones exitosas**, incluido el retiro de E002 durante vacaciones; consultar
[`EVIDENCIAS.md`](EVIDENCIAS.md).

## Decisiones técnicas del enunciado

Registrar aquí la versión corta. El "por qué" largo está en `PLAN-RETO5.md` y, al cerrar la
etapa, debe copiarse al README que el PDF pide. Mientras la etapa no cierre, queda como
"propuesta" si alguien la cambia; las de esta tabla ya están tomadas en el plan.

| Decisión | Estado | Elección tomada |
|---|---|---|
| Lenguaje de auth | Tomada | Python 3.12 + FastAPI. No hay quinto lenguaje: el curso ya tiene cuatro. Carpeta, proceso, Postgres y Alembic propios, separados de notificaciones. |
| Dónde vive el JWT | Tomada | Validación HS256 en el API Gateway. `auth-service` vuelve a verificar solo `POST /auth/change-password`. Secreto `JWT_SECRET` por entorno. Access token 60 min, `sub` = `empleadoId`, `role` = `ADMIN` o `USER`. |
| Token de reset | Tomada | Opción A del PDF: JWT con `type` = `RESET_PASSWORD`, 60 min. No se persiste. No sirve como Bearer. |
| Estados de la cuenta | Tomada | `PENDIENTE_ACTIVACION`, `ACTIVA`, `SUSPENDIDA_TEMPORAL`, `DESACTIVADA_PERMANENTE`. |
| Puerto | Tomada | `8086` interno. El `:8085` del diagrama del PDF ya es vacaciones. |
| Bienvenida | Tomada | El correo con el token es `usuario.creado` (`SEGURIDAD`). `empleado.creado` solo guarda el destinatario. El adiós es `cuenta.desactivada`, no `DESVINCULACION`. |
| Scheduler | Tomada | `node-cron`, `VACACIONES_CRON` por defecto cada minuto. Demo Newman con `forzar-inicio` y `forzar-fin`. Una instancia; el lock queda para el Reto 31. |
| Empleado `EN_VACACIONES` | Tomada | No se cambia en este reto. Se suspende la cuenta, no la fila del empleado. |

## Cómo actualizar este archivo

1. Cambia el estado de la etapa que avanzaste (⬜ → 🔄 → ✅).
2. Si tomaste una decisión distinta a la propuesta en `PLAN-RETO5.md`, anótalo en "Notas" y
   actualiza también la tabla de decisiones técnicas de arriba.
3. Si una etapa queda bloqueada (falta la semilla, el cron se come el período de la prueba,
   el caso borde reactiva la cuenta), anótalo en "Notas" en vez de dejarla en 🔄 silenciosamente.
4. El caso borde no se marca ✅ solo porque el estado `DESACTIVADA_PERMANENTE` exista: hay que
   ver el login de E002 seguir en 401 después de `vacaciones.finalizadas`.
