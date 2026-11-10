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
4. Recrear bases/volúmenes en desarrollo cuando cambien sus credenciales iniciales.
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
