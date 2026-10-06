from notificaciones.main import app


def test_openapi_documenta_bearer_solo_en_las_consultas_protegidas():
    spec = app.openapi()

    assert spec["components"]["securitySchemes"]["BearerAuth"]["scheme"] == "bearer"
    assert spec["paths"]["/notificaciones"]["get"]["security"] == [{"BearerAuth": []}]
    assert spec["paths"]["/notificaciones/{empleado_id}"]["get"]["security"] == [{"BearerAuth": []}]
    assert "security" not in spec["paths"]["/health"]["get"]
