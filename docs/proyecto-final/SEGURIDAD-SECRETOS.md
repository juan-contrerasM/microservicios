# Gestión de secretos

## Creación local

El repositorio solo conserva nombres y marcadores. Cada integrante genera valores diferentes:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup-secrets.ps1
```

En Linux o macOS:

```sh
./scripts/setup-secrets.sh
```

Los comandos crean `.env` y `secrets/`. Ninguno debe añadirse a Git, enviarse por chat o aparecer
en capturas. Los archivos generados son la fuente local durante la migración a Compose Secrets.

### Webhook de Discord (Fase 11)

`secrets/discord_webhook_url.txt` es el único secreto que no se genera al azar: lo entrega Discord
(Ajustes del canal → Integraciones → Webhooks). Los scripts lo toman de la variable de entorno
`DISCORD_WEBHOOK_URL`; sin ella escriben un marcador que Grafana acepta pero que no entrega. Para
fijarlo sin rotar los demás secretos:

```sh
printf '%s' 'https://discord.com/api/webhooks/<id>/<token>' > secrets/discord_webhook_url.txt
docker compose up -d --force-recreate grafana
```

Grafana lo monta en `/run/secrets/discord_webhook_url` y lo lee con `$__file{...}` desde
`observability/grafana/provisioning/alerting/notificaciones.yml`; la URL nunca aparece en Git, en
`.env` ni en la configuración exportada.

## Verificación antes de cada push

Árbol de trabajo actual:

```powershell
.\scripts\scan-secrets.ps1
```

Historial completo de la rama:

```powershell
.\scripts\scan-secrets.ps1 -History
```

Las variantes `.sh` aceptan respectivamente ningún argumento o `--history`. La salida de Gitleaks
se ejecuta con `--redact`; no copie un valor detectado en una incidencia.

## Rotación

1. Detener consumidores y productores que usen el secreto afectado.
2. Respaldar únicamente datos, nunca el archivo `.env` dentro del repositorio.
3. Ejecutar el generador con `-Force` o `--force`. Esto rota todos los valores locales.
4. Recrear bases/volúmenes en desarrollo cuando cambien sus credenciales iniciales: PostgreSQL,
   MySQL y RabbitMQ solo leen la contraseña al crear el volumen. `docker compose down -v` (borra los
   datos) o un nombre de proyecto nuevo (`docker compose -p <nombre> up --build`). Sin esto los
   servicios no autentican contra su base o el broker y quedan `unhealthy`.
5. Levantar el stack, comprobar health, login, publicación y consumo AMQP.
6. Revocar el valor anterior en Discord, Registry o proveedor externo cuando corresponda.
7. Ejecutar ambos modos del escáner antes de publicar cambios.

Si un secreto real llegó a Git, borrarlo en un commit nuevo no lo revoca: debe rotarse inmediatamente.
La reescritura del historial solo se realiza coordinadamente con todo el equipo y después de revocar.

## Revocación específica

- Discord: eliminar el webhook comprometido y crear uno nuevo; nunca compartir credenciales de la
  cuenta de Discord.
- JWT: rotar la clave invalida todos los tokens activos; hacerlo en una ventana controlada.
- Bases y RabbitMQ: cambiar el usuario/clave en el servidor y luego recrear los consumidores.
- Jenkins, Grafana y SonarQube: cambiar la credencial administrativa desde el servicio y actualizar
  el secreto local; no mantener contraseñas de referencia académica.

## Permisos de `secrets/` con contenedores no root (Fase 7)

Los siete servicios corren como UID/GID fijo `10001` (E02). Compose sin Swarm no copia los
secretos: los monta como bind mount de solo lectura en `/run/secrets/<nombre>` conservando dueño
y modo del host. Con el modo anterior (0600, dueño = usuario del host) el UID 10001 no podía
leerlos y los servicios no arrancaban.

**Decisión:** archivos `secrets/*.txt` en 0644 dentro de un directorio `secrets/` en 0700.

- En el host, el directorio 0700 impide a cualquier otro usuario atravesarlo, así que el bit de
  lectura "otros" de los archivos no expone nada fuera del dueño (y de root, que ya podía leerlos).
- En el contenedor solo existe el archivo montado; el directorio del host no se ve. Cada servicio
  recibe únicamente los secretos que declara en `secrets:` de `docker-compose.yml`.
- `.env` y `e2e-tests/.env` siguen en 0600: nunca se montan en un contenedor de servicio.
- El script crea todo con `umask 077` y solo al final abre los `.txt` a 0644, de modo que no hay
  ventana en la que el directorio sea accesible a otros.

**Alternativas descartadas:**

- `chown 10001` de los archivos: exige `sudo` en el host y deja archivos que el propio usuario no
  puede leer ni rotar sin privilegios.
- Un entrypoint que arranque como root, copie los secretos y baje a 10001 (`su-exec`/`gosu`):
  mantiene una fase root y un binario extra para cambiar de usuario en cada imagen; contradice E02.
- `uid`/`gid`/`mode` en la sintaxis larga de `secrets:` de Compose: según la versión de Compose,
  con `file:` y sin Swarm se ignora o no es portable entre las versiones que usa el equipo.

**Migración:** quien ya tenga `secrets/` de una versión anterior ejecuta
`./scripts/setup-secrets.sh --permisos` (no rota valores). En Windows con Docker Desktop los
archivos ya se ven legibles en el contenedor; la protección es la ACL del perfil de usuario y
`setup-secrets.ps1` no cambia permisos. En pwsh sobre Linux/macOS, `setup-secrets.ps1` aplica los
mismos modos que la variante `.sh` y acepta `-Permisos`.

**Verificación:** `docker compose exec auth-service sh -c 'id && test -r /run/secrets/jwt_secret && echo legible'`
debe mostrar `uid=10001` y `legible`, sin imprimir el valor.
