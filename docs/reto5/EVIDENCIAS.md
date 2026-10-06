# Evidencias de validación — Reto 5

Validación ejecutada el **5 de octubre de 2026** (America/Bogota) desde una instalación limpia:

```bash
docker compose --env-file .env.example down --volumes --remove-orphans
docker compose --env-file .env.example up --build -d
```

Los 15 contenedores quedaron en ejecución; Gateway, servicios, bases de datos y RabbitMQ
reportaron `healthy`. El único puerto HTTP de negocio publicado fue `8080`.

## Recorrido funcional

Se ejecutaron en orden las carpetas 0 a 10 de
[`Reto5.postman_collection.json`](Reto5.postman_collection.json) contra
`http://127.0.0.1:8080`:

```text
iterations:          1 ejecutada,   0 fallidas
requests:           84 ejecutadas, 0 fallidas
test-scripts:       58 ejecutados, 0 fallidos
assertions:        101 ejecutadas, 0 fallidas
duración total:     35.9 s
```

La colección espera la propagación asíncrona de los eventos antes de evaluar cada resultado.
Por eso el número de solicitudes es mayor que el número de casos visibles en Postman.

| # | Qué se comprobó | Resultado observado |
|---|---|---|
| 1 | Alta protegida | `GET /empleados` sin token y con firma alterada respondió `401`. Después de activar E001, su JWT tuvo `sub=E001`, `role=USER` y la consulta respondió `200`. |
| 2 | RBAC y propiedad | El USER recibió `403` al retirar E001 y al crear un departamento. `PUT /perfiles/E001` respondió `200`, mientras `PUT /perfiles/E002` respondió `403`. |
| 3 | Recuperación y cambio de clave | Recover mantuvo una respuesta genérica incluso para un correo inexistente. Reset y change-password aceptaron claves válidas, rechazaron la política inválida y el login con la clave anterior dejó de funcionar. |
| 4 | Vacaciones | `forzar-inicio` dejó el período `EN_CURSO`, produjo el aviso `CUENTA` y el login respondió `401`. `forzar-fin` dejó `FINALIZADA`, reactivó E001 y el login volvió a `200`. |
| 5 | Caso borde | E002 fue retirado durante `EN_CURSO`. Tras `forzar-fin`, nueve reintentos de login siguieron en `401` y no apareció una segunda bienvenida de regreso. |
| 6 | Offboarding | E001 y E002 quedaron auditables como `RETIRADO`; sus cuentas quedaron desactivadas permanentemente y el perfil de E001 quedó archivado. |
| 7 | Secreto y puerto | Compose exige `JWT_SECRET` desde el entorno. `curl --connect-timeout 2 http://127.0.0.1:8086/health` terminó por timeout, porque auth solo usa `expose: 8086`. |
| 8 | OpenAPI | Los contratos internos de auth, empleados, departamentos, perfiles, notificaciones y vacaciones contienen `BearerAuth`. Login, recover, reset y health permanecen públicos donde corresponde. |

## Pruebas automatizadas y compilación

| Componente | Resultado |
|---|---|
| `api-gateway` | 20 tests Node exitosos |
| `empleados-service` | 66 tests Maven exitosos |
| `departamentos-service` | `go test ./...` exitoso con Go 1.25; los paquetes actuales no contienen tests unitarios |
| `perfiles-service` | 4 tests Maven exitosos |
| `notificaciones-service` | 11 tests pytest exitosos |
| `vacaciones-service` | 19 tests Node exitosos |
| `auth-service` | 6 tests pytest exitosos |
| Imágenes Docker | Los siete servicios construyeron correctamente desde sus Dockerfiles |

Además, `docker compose config --quiet` validó la configuración y una revisión de los logs de
los últimos dos minutos no mostró `ERROR`, excepciones, fallos fatales ni *panics* después de
estabilizar el arranque.

## Reproducción con Newman

Sobre volúmenes limpios y con los contenedores saludables:

```bash
npx newman run docs/reto5/Reto5.postman_collection.json \
  --folder "0. Salud y login de admin" \
  --folder "1. Alta con JWT de admin" \
  --folder "2. Petición denegada" \
  --folder "3. Activar E001 y leer" \
  --folder "4. Recuperar contraseña" \
  --folder "5. Escritura denegada y propiedad" \
  --folder "6. Cambio de contraseña" \
  --folder "7. Vacaciones: suspensión y reactivación" \
  --folder "8. Caso borde: retiro durante las vacaciones" \
  --folder "9. Offboarding de E001" \
  --folder "10. Reglas que el USER no salta" \
  --env-var "gateway_url=http://127.0.0.1:8080"
```

La carpeta 11 es deliberadamente manual: demuestra desde el host que el puerto interno de auth
no fue publicado.
