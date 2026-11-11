import json
import os
from functools import lru_cache
from pathlib import Path

RUTA_PREDETERMINADA = Path(__file__).resolve().parents[2] / "config" / "templates.json"


@lru_cache(maxsize=8)
def _cargar(ruta: str) -> dict:
    with Path(ruta).open(encoding="utf-8") as archivo:
        plantillas = json.load(archivo)
    if not isinstance(plantillas, dict):
        raise ValueError("El archivo de plantillas debe contener un objeto JSON")
    return plantillas


def renderizar(evento: str, datos: dict) -> tuple[str, str, str]:
    ruta = os.getenv("NOTIFICACIONES_TEMPLATES_FILE", str(RUTA_PREDETERMINADA))
    plantilla = _cargar(ruta)[evento]
    return (
        plantilla["tipo"],
        plantilla["asunto"].format_map(datos),
        plantilla["mensaje"].format_map(datos),
    )
