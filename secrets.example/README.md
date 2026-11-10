# Secretos locales

Este directorio documenta los nombres esperados, pero no contiene valores reales. Ejecute desde la
raíz del repositorio:

- Windows: `./scripts/setup-secrets.ps1`
- Linux/macOS: `./scripts/setup-secrets.sh`

Los scripts crean `.env` y `secrets/` con valores aleatorios. Ambos destinos están ignorados por
Git. Nunca copie secretos reales a `secrets.example/`.
