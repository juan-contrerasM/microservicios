"""Exporta el OpenAPI que sirve la app a ``openapi.yaml`` en la raíz del servicio.

Uso (desde ``services/auth-service``)::

    python -m auth_service.contrato          # reescribe openapi.yaml
    python -m auth_service.contrato --check  # sale con 1 si openapi.yaml quedó desactualizado

La fuente de verdad es el código (FastAPI genera el contrato); ``openapi.yaml``
es la copia versionada que exige el estándar E08. ``tests/test_contrato.py``
falla si ambos difieren, así que cualquier cambio de endpoint obliga a
regenerar el archivo en el mismo commit.
"""

import json
import sys
from contextlib import contextmanager
from pathlib import Path

RUTA_YAML = Path(__file__).resolve().parents[2] / "openapi.yaml"


def generar_contrato() -> dict:
    """Contrato tal como lo serviría ``GET /openapi.json`` (sin BD ni broker)."""
    from auth_service.config import Settings
    from auth_service.consumidor import EstadoConsumidor
    from auth_service.main import crear_app
    from auth_service.seguridad import Tokens

    settings = Settings(jwt_secret="solo-para-exportar", consumer_disabled=True)

    @contextmanager
    def sin_repo():
        raise RuntimeError("el exportador no abre repositorios")
        yield  # pragma: no cover

    app = crear_app(
        settings,
        sin_repo,
        None,
        EstadoConsumidor(),
        lambda: (True, True),
        Tokens(settings.jwt_secret, 1, 1),
    )
    # Ida y vuelta por JSON: el YAML debe tener exactamente lo que viaja por HTTP.
    return json.loads(json.dumps(app.openapi()))


def como_yaml(contrato: dict) -> str:
    import yaml

    return yaml.safe_dump(contrato, sort_keys=False, allow_unicode=True, width=120)


def main(argv: list[str]) -> int:
    texto = como_yaml(generar_contrato())
    if "--check" in argv:
        actual = RUTA_YAML.read_text(encoding="utf-8") if RUTA_YAML.exists() else ""
        if actual != texto:
            print(f"{RUTA_YAML.name} desactualizado: ejecute python -m auth_service.contrato", file=sys.stderr)
            return 1
        return 0
    RUTA_YAML.write_text(texto, encoding="utf-8", newline="\n")
    print(f"Escrito {RUTA_YAML}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
