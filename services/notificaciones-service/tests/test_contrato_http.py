"""Fase 5: contrato HTTP de notificaciones-service (health C2, error C3, OpenAPI E08)."""

import time

import pytest
import yaml
from openapi_spec_validator import validate
from fastapi.testclient import TestClient
from sqlalchemy.exc import OperationalError

from notificaciones import __version__, contrato, main
from notificaciones.salud import con_timeout

CAMPOS_C3 = {"timestamp", "status", "error", "message", "path", "traceId"}
TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


@pytest.fixture
def cliente(monkeypatch):
    # Sin "with": no corre el lifespan, así que no arranca el consumidor real de RabbitMQ.
    monkeypatch.setattr(main.settings, "consumer_disabled", False)
    monkeypatch.setattr(main.estado_consumidor, "conectado", True)
    monkeypatch.setattr(main, "base_responde", lambda _motor: True)
    yield TestClient(main.app, raise_server_exceptions=False)
    main.app.dependency_overrides.clear()


# --- Health (C2) -----------------------------------------------------------------


def test_health_up_incluye_version_y_checks(cliente):
    respuesta = cliente.get("/health")
    assert respuesta.status_code == 200
    assert respuesta.json() == {
        "status": "UP",
        "service": "notificaciones-service",
        "version": __version__,
        "checks": {"database": "UP", "messageBroker": "UP"},
    }


def test_health_down_con_base_caida(cliente, monkeypatch):
    monkeypatch.setattr(main, "base_responde", lambda _motor: False)
    respuesta = cliente.get("/health")
    assert respuesta.status_code == 503
    assert respuesta.json()["status"] == "DOWN"
    assert respuesta.json()["checks"] == {"database": "DOWN", "messageBroker": "UP"}


def test_health_down_con_broker_desconectado(cliente, monkeypatch):
    monkeypatch.setattr(main.estado_consumidor, "conectado", False)
    respuesta = cliente.get("/health")
    assert respuesta.status_code == 503
    assert respuesta.json()["checks"] == {"database": "UP", "messageBroker": "DOWN"}


def test_health_responde_en_menos_de_3s_con_base_colgada(cliente, monkeypatch):
    def colgada(_motor):
        time.sleep(10)
        return True

    monkeypatch.setattr(main, "base_responde", colgada)
    inicio = time.monotonic()
    respuesta = cliente.get("/health")
    assert time.monotonic() - inicio < 3
    assert respuesta.status_code == 503
    assert respuesta.json()["checks"]["database"] == "DOWN"


def test_con_timeout_devuelve_el_valor_o_el_defecto():
    assert con_timeout(lambda: True, 1, False) is True
    assert con_timeout(lambda: 1 / 0, 1, False) is False
    assert con_timeout(lambda: time.sleep(5), 0.2, "DOWN") == "DOWN"


# --- Error uniforme (C3) -----------------------------------------------------------


def _es_c3(cuerpo: dict, status: int, path: str):
    assert set(cuerpo) == CAMPOS_C3
    assert cuerpo["status"] == status
    assert cuerpo["path"] == path
    assert cuerpo["timestamp"].endswith("Z")


class _SesionQueFalla:
    def __init__(self, error):
        self._error = error

    def scalars(self, _consulta):
        raise self._error


def _sesion_con(error):
    def obtener():
        yield _SesionQueFalla(error)

    return obtener


def test_base_caida_en_consulta_responde_503_c3(cliente):
    error = OperationalError("SELECT", {}, Exception("could not connect to 10.0.0.9"))
    main.app.dependency_overrides[main.obtener_sesion] = _sesion_con(error)
    respuesta = cliente.get("/notificaciones", headers={"traceparent": TRACEPARENT})
    assert respuesta.status_code == 503
    _es_c3(respuesta.json(), 503, "/notificaciones")
    assert respuesta.json()["traceId"] == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert "10.0.0.9" not in respuesta.text


def test_error_inesperado_responde_500_sin_stack_trace(cliente):
    main.app.dependency_overrides[main.obtener_sesion] = _sesion_con(KeyError("detalle-interno"))
    respuesta = cliente.get("/notificaciones/E001")
    assert respuesta.status_code == 500
    _es_c3(respuesta.json(), 500, "/notificaciones/E001")
    assert respuesta.json()["message"] == "Error interno del servidor"
    assert "detalle-interno" not in respuesta.text
    assert "Traceback" not in respuesta.text


def test_ruta_inexistente_y_metodo_no_permitido_siguen_c3(cliente):
    no_existe = cliente.get("/notificaciones/E001/extra")
    metodo = cliente.post("/notificaciones")
    assert no_existe.status_code == 404
    _es_c3(no_existe.json(), 404, "/notificaciones/E001/extra")
    assert no_existe.json()["error"] == "Not Found"
    assert metodo.status_code == 405
    _es_c3(metodo.json(), 405, "/notificaciones")


# --- OpenAPI (E08) -----------------------------------------------------------------


def test_openapi_yaml_exportado_no_difiere_del_que_sirve_la_app(cliente):
    servido = cliente.get("/openapi.json").json()
    exportado = yaml.safe_load(contrato.RUTA_YAML.read_text(encoding="utf-8"))
    assert exportado == servido, "openapi.yaml desactualizado: ejecute python -m notificaciones.contrato"
    assert contrato.main(["--check"]) == 0


def test_openapi_documenta_codigos_ejemplos_y_bearer():
    spec = contrato.generar_contrato()
    paths = spec["paths"]
    assert set(paths) == {"/health", "/notificaciones", "/notificaciones/{empleado_id}"}
    assert "security" not in paths["/health"]["get"]
    assert set(paths["/health"]["get"]["responses"]) == {"200", "503"}
    for ruta in ("/notificaciones", "/notificaciones/{empleado_id}"):
        operacion = paths[ruta]["get"]
        assert operacion["security"] == [{"BearerAuth": []}]
        assert set(operacion["responses"]) == {"200", "401", "500", "503"}
    esquemas = spec["components"]["schemas"]
    assert set(esquemas["ErrorRespuesta"]["properties"]) == CAMPOS_C3
    assert esquemas["NotificacionRespuesta"]["examples"]
    assert "HTTPValidationError" not in esquemas


def test_openapi_publicado_es_valido():
    validate(contrato.generar_contrato())


def test_swagger_ui_interactiva_disponible(cliente):
    docs = cliente.get("/docs")
    atajo = cliente.get("/swagger", follow_redirects=False)
    assert docs.status_code == 200
    assert "swagger-ui" in docs.text
    assert atajo.headers["location"] == "/docs"


def test_main_reescribe_el_yaml(tmp_path, monkeypatch):
    monkeypatch.setattr(contrato, "RUTA_YAML", tmp_path / "openapi.yaml")
    assert contrato.main(["--check"]) == 1
    assert contrato.main([]) == 0
    assert contrato.main(["--check"]) == 0
