"""Exporta el OpenAPI que sirve la app a ``openapi.yaml`` en la raíz del servicio.

Uso (desde ``services/notificaciones-service``)::

    PYTHONPATH=src python -m notificaciones.contrato          # reescribe openapi.yaml
    PYTHONPATH=src python -m notificaciones.contrato --check  # sale con 1 si quedó desactualizado

La fuente de verdad es el código (FastAPI genera el contrato); ``openapi.yaml``
es la copia versionada que exige el estándar E08. ``tests/test_contrato_http.py``
falla si ambos difieren.
"""

import json
import sys
from pathlib import Path

RUTA_YAML = Path(__file__).resolve().parents[2] / "openapi.yaml"


def generar_contrato() -> dict:
    """Contrato tal como lo serviría ``GET /openapi.json`` (importar la app no conecta a la BD)."""
    from notificaciones.main import app

    return json.loads(json.dumps(app.openapi()))


def como_yaml(contrato: dict) -> str:
    import yaml

    return yaml.safe_dump(contrato, sort_keys=False, allow_unicode=True, width=120)


def main(argv: list[str]) -> int:
    texto = como_yaml(generar_contrato())
    if "--check" in argv:
        actual = RUTA_YAML.read_text(encoding="utf-8") if RUTA_YAML.exists() else ""
        if actual != texto:
            print(f"{RUTA_YAML.name} desactualizado: ejecute python -m notificaciones.contrato", file=sys.stderr)
            return 1
        return 0
    RUTA_YAML.write_text(texto, encoding="utf-8", newline="\n")
    print(f"Escrito {RUTA_YAML}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
