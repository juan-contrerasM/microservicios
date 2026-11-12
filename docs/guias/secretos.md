# Secretos

- **Generar:** `bash scripts/setup-secrets.sh` (o `.\scripts\setup-secrets.ps1`) crea `.env` y 16
  archivos en `secrets/` con valores aleatorios. `--force` rota todos; `--permisos` solo corrige permisos.
- **Uso:** Docker Compose Secrets montados en `/run/secrets/<nombre>`; los servicios leen `*_FILE`.
- **Verificar:** `bash scripts/scan-secrets.sh` (árbol) y `bash scripts/scan-secrets.sh --history`.
  Los pipelines repiten el escaneo con Gitleaks en cada build.
- **Rotación y revocación:** [`../proyecto-final/SEGURIDAD-SECRETOS.md`](../proyecto-final/SEGURIDAD-SECRETOS.md).
