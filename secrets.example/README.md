# Secretos locales

Este directorio documenta los nombres esperados, pero no contiene valores reales. Ejecute desde la
raíz del repositorio:

- Windows: `./scripts/setup-secrets.ps1`
- Linux/macOS: `./scripts/setup-secrets.sh`

Los scripts crean `.env` y `secrets/` con valores aleatorios. Ambos destinos están ignorados por
Git. Nunca copie secretos reales a `secrets.example/`.

## Permisos y contenedores no root

Desde la Fase 7 los siete servicios corren como UID/GID `10001`, no como root. Docker Compose
sin Swarm monta cada archivo de `secrets/` en `/run/secrets/<nombre>` como bind mount **con los
permisos del host**: un archivo 0600 de otro usuario sería ilegible y el servicio fallaría al
arrancar (por ejemplo, `No se puede leer DB_PASSWORD_FILE` en los servicios Java).

Por eso `setup-secrets.sh` deja:

| Ruta | Modo | Motivo |
|---|---|---|
| `secrets/` | 0700 | Ningún otro usuario del host puede listar ni abrir los secretos |
| `secrets/*.txt` | 0644 | Legibles por el UID 10001 dentro del contenedor |
| `.env`, `e2e-tests/.env` | 0600 | Solo los lee el usuario que ejecuta Compose y las pruebas |

Si generó los secretos con una versión anterior (archivos 0600), corrija solo los permisos sin
rotar valores: `./scripts/setup-secrets.sh --permisos` (o `./scripts/setup-secrets.ps1 -Permisos`
en pwsh sobre Linux/macOS). En Windows con Docker Desktop no hace falta: los archivos ya se ven
legibles dentro del contenedor y la protección es la ACL del perfil de usuario.
