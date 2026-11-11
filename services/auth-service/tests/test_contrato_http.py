"""Fase 5: contrato HTTP de auth-service (health C2, error C3, OpenAPI E08)."""

import time

import yaml
from fastapi.testclient import TestClient
from sqlalchemy.exc import OperationalError

from auth_service import __version__, contrato
from auth_service.consumidor import EstadoConsumidor
from auth_service.main import crear_app
from auth_service.salud import con_timeout
from soporte import Memoria, PublicadorMemoria, abrir, app_de_prueba, settings_de_prueba, tokens_de

CAMPOS_C3 = {"timestamp", "status", "error", "message", "path", "traceId"}
TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


def _app(comprobar_salud=lambda: (True, True), abrir_repo=None):
    settings = settings_de_prueba()
    repo = Memoria()
    return crear_app(
        settings,
        abrir_repo or (lambda: abrir(repo)),
        PublicadorMemoria(),
        EstadoConsumidor(),
        comprobar_salud,
        tokens_de(settings),
    )


# --- Health (C2) -----------------------------------------------------------------


def test_health_up_incluye_version_y_checks():
    with TestClient(_app()) as cliente:
        respuesta = cliente.get("/health")
    assert respuesta.status_code == 200
    assert respuesta.json() == {
        "status": "UP",
        "service": "auth-service",
        "version": __version__,
        "checks": {"database": "UP", "messageBroker": "UP"},
    }


def test_health_down_con_base_caida_responde_503():
    with TestClient(_app(lambda: (False, True))) as cliente:
        respuesta = cliente.get("/health")
    assert respuesta.status_code == 503
    assert respuesta.json()["status"] == "DOWN"
    assert respuesta.json()["checks"] == {"database": "DOWN", "messageBroker": "UP"}


def test_health_down_con_broker_caido_responde_503():
    with TestClient(_app(lambda: (True, False))) as cliente:
        respuesta = cliente.get("/health")
    assert respuesta.status_code == 503
    assert respuesta.json()["checks"] == {"database": "UP", "messageBroker": "DOWN"}


def test_health_responde_en_menos_de_3s_con_dependencia_colgada():
    def colgada():
        time.sleep(10)
        return True, True

    with TestClient(_app(colgada)) as cliente:
        inicio = time.monotonic()
        respuesta = cliente.get("/health")
        duracion = time.monotonic() - inicio
    assert duracion < 3
    assert respuesta.status_code == 503
    assert respuesta.json()["checks"] == {"database": "DOWN", "messageBroker": "DOWN"}


def test_con_timeout_devuelve_el_valor_o_el_defecto():
    assert con_timeout(lambda: True, 1, False) is True
    assert con_timeout(lambda: 1 / 0, 1, False) is False
    inicio = time.monotonic()
    assert con_timeout(lambda: time.sleep(5), 0.2, "DOWN") == "DOWN"
    assert time.monotonic() - inicio < 1


# --- Error uniforme (C3) -----------------------------------------------------------


def _es_c3(cuerpo: dict, status: int, path: str):
    assert set(cuerpo) == CAMPOS_C3
    assert cuerpo["status"] == status
    assert cuerpo["path"] == path
    assert cuerpo["timestamp"].endswith("Z")
    assert "Traceback" not in cuerpo["message"]


def test_401_de_login_sigue_c3_con_trace_id_del_traceparent():
    with TestClient(_app()) as cliente:
        respuesta = cliente.post(
            "/auth/login",
            json={"usuario": "admin", "contrasena": "no-es"},
            headers={"traceparent": TRACEPARENT},
        )
    assert respuesta.status_code == 401
    _es_c3(respuesta.json(), 401, "/auth/login")
    assert respuesta.json()["error"] == "Unauthorized"
    assert respuesta.json()["message"] == "Credenciales inválidas"
    assert respuesta.json()["traceId"] == "4bf92f3577b34da6a3ce929d0e0e4736"


def test_validacion_responde_400_no_422():
    with TestClient(_app()) as cliente:
        sin_campos = cliente.post("/auth/login", json={})
        no_json = cliente.post("/auth/login", content="{", headers={"Content-Type": "application/json"})
    assert sin_campos.status_code == 400
    _es_c3(sin_campos.json(), 400, "/auth/login")
    assert "usuario: es obligatorio" in sin_campos.json()["message"]
    assert no_json.status_code == 400
    assert no_json.json()["message"] == "El cuerpo de la petición no es JSON válido"


def test_change_password_sin_token_responde_401_c3():
    with TestClient(_app()) as cliente:
        respuesta = cliente.post(
            "/auth/change-password", json={"contrasenaActual": "x", "contrasenaNueva": "Nueva1234!"}
        )
    assert respuesta.status_code == 401
    assert respuesta.headers["www-authenticate"] == "Bearer"
    _es_c3(respuesta.json(), 401, "/auth/change-password")
    assert respuesta.json()["message"] == "No autenticado"


def test_reset_con_token_invalido_responde_400_c3():
    with TestClient(_app()) as cliente:
        respuesta = cliente.post("/auth/reset-password", json={"token": "basura", "contrasena": "Nueva1234!"})
    assert respuesta.status_code == 400
    _es_c3(respuesta.json(), 400, "/auth/reset-password")


def test_ruta_inexistente_y_metodo_no_permitido_siguen_c3():
    with TestClient(_app()) as cliente:
        no_existe = cliente.get("/auth/register")
        metodo = cliente.get("/auth/login")
    assert no_existe.status_code == 404
    _es_c3(no_existe.json(), 404, "/auth/register")
    assert no_existe.json()["message"] == "Recurso no encontrado"
    assert metodo.status_code == 405
    _es_c3(metodo.json(), 405, "/auth/login")


def _repo_que_falla(error):
    """La primera apertura (siembra del admin al arrancar) funciona; las demás fallan."""
    from contextlib import contextmanager

    llamadas = []
    repo = Memoria()

    @contextmanager
    def falla():
        raise error
        yield  # pragma: no cover

    def abrir_repo():
        llamadas.append(1)
        return abrir(repo) if len(llamadas) == 1 else falla()

    return abrir_repo


def test_base_caida_responde_503_sin_detalles_del_driver():
    error = OperationalError("SELECT 1", {}, Exception("connection to server at 10.0.0.5 failed"))
    with TestClient(_app(abrir_repo=_repo_que_falla(error)), raise_server_exceptions=False) as cliente:
        respuesta = cliente.post("/auth/login", json={"usuario": "admin", "contrasena": "x"})
    assert respuesta.status_code == 503
    _es_c3(respuesta.json(), 503, "/auth/login")
    assert "10.0.0.5" not in respuesta.text


def test_error_inesperado_responde_500_sin_stack_trace():
    # La siembra del admin captura sus errores; el login no.
    with TestClient(_app(abrir_repo=_repo_que_falla(KeyError("secreto-interno"))), raise_server_exceptions=False) as c:
        respuesta = c.post("/auth/login", json={"usuario": "admin", "contrasena": "x"})
    assert respuesta.status_code == 500
    _es_c3(respuesta.json(), 500, "/auth/login")
    assert respuesta.json()["message"] == "Error interno del servidor"
    assert "secreto-interno" not in respuesta.text
    assert "Traceback" not in respuesta.text


# --- OpenAPI (E08) -----------------------------------------------------------------


def test_openapi_yaml_exportado_no_difiere_del_que_sirve_la_app():
    with TestClient(app_de_prueba(Memoria(), PublicadorMemoria())) as cliente:
        servido = cliente.get("/openapi.json").json()
    exportado = yaml.safe_load(contrato.RUTA_YAML.read_text(encoding="utf-8"))
    assert exportado == servido, "openapi.yaml desactualizado: ejecute python -m auth_service.contrato"
    assert contrato.main(["--check"]) == 0


def test_openapi_documenta_bearer_codigos_y_ejemplos():
    spec = contrato.generar_contrato()
    paths = spec["paths"]
    assert set(paths) == {
        "/health",
        "/auth/login",
        "/auth/recover-password",
        "/auth/reset-password",
        "/auth/change-password",
    }
    assert spec["components"]["securitySchemes"]["BearerAuth"]["scheme"] == "bearer"
    for publico in ("/auth/login", "/auth/recover-password", "/auth/reset-password"):
        assert "security" not in paths[publico]["post"]
    assert "security" not in paths["/health"]["get"]
    assert paths["/auth/change-password"]["post"]["security"] == [{"BearerAuth": []}]
    assert set(paths["/auth/change-password"]["post"]["responses"]) == {"200", "400", "401", "500", "503"}
    assert set(paths["/health"]["get"]["responses"]) == {"200", "503"}
    for operaciones in paths.values():
        for operacion in operaciones.values():
            assert "422" not in operacion["responses"]
    esquemas = spec["components"]["schemas"]
    assert set(esquemas["ErrorRespuesta"]["properties"]) == CAMPOS_C3
    for cuerpo in ("LoginCuerpo", "RecuperarCuerpo", "ResetCuerpo", "CambioCuerpo"):
        assert esquemas[cuerpo]["examples"], cuerpo


def test_swagger_ui_interactiva_disponible():
    with TestClient(_app()) as cliente:
        docs = cliente.get("/docs")
        atajo = cliente.get("/swagger", follow_redirects=False)
    assert docs.status_code == 200
    assert "swagger-ui" in docs.text
    assert atajo.status_code in (302, 307)
    assert atajo.headers["location"] == "/docs"


def test_main_reescribe_el_yaml(tmp_path, monkeypatch):
    destino = tmp_path / "openapi.yaml"
    monkeypatch.setattr(contrato, "RUTA_YAML", destino)
    assert contrato.main(["--check"]) == 1
    assert contrato.main([]) == 0
    assert contrato.main(["--check"]) == 0
