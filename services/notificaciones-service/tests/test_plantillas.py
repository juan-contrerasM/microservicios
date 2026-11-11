import json

from notificaciones.plantillas import _cargar, renderizar


def test_usa_un_archivo_configurable(monkeypatch, tmp_path):
    ruta = tmp_path / "templates.json"
    ruta.write_text(
        json.dumps({
            "cuenta.activada": {
                "tipo": "PERSONALIZADA",
                "asunto": "Cuenta de {empleadoId}",
                "mensaje": "Hola {nombre}",
            }
        }),
        encoding="utf-8",
    )
    monkeypatch.setenv("NOTIFICACIONES_TEMPLATES_FILE", str(ruta))
    _cargar.cache_clear()

    tipo, asunto, mensaje = renderizar(
        "cuenta.activada", {"empleadoId": "E001", "nombre": "Ana"}
    )

    assert (tipo, asunto, mensaje) == ("PERSONALIZADA", "Cuenta de E001", "Hola Ana")


def test_rechaza_un_catalogo_que_no_sea_objeto(monkeypatch, tmp_path):
    ruta = tmp_path / "templates.json"
    ruta.write_text("[]", encoding="utf-8")
    monkeypatch.setenv("NOTIFICACIONES_TEMPLATES_FILE", str(ruta))
    _cargar.cache_clear()

    try:
        renderizar("cuenta.activada", {})
        assert False, "Se esperaba ValueError"
    except ValueError as error:
        assert "objeto JSON" in str(error)
