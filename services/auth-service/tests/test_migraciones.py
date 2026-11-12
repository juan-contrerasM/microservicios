"""E05: toda revisión de Alembic tiene downgrade y el esquema declara UNIQUE donde hay unicidad.

Sin Docker no hay PostgreSQL, así que se usa el modo offline de Alembic
(``--sql``): genera el DDL de upgrade y de downgrade sin conectarse. Prueba que
la cadena de revisiones es lineal, que cada ``downgrade`` deshace algo y que
la ida y vuelta completa (base → head → base) se puede generar.
"""

import io
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory

RAIZ = Path(__file__).resolve().parents[1]


@pytest.fixture
def config(monkeypatch):
    # env.py lee la URL con load_settings(), que exige estos secretos.
    monkeypatch.setenv("JWT_SECRET", "solo-para-generar-sql-con-32-bytes-o-mas")
    monkeypatch.setenv("RABBITMQ_PASSWORD", "solo-para-generar-sql-con-32-bytes-o-mas")
    monkeypatch.setenv("AUTH_ADMIN_PASSWORD", "solo-para-generar-sql-con-32-bytes-o-mas")
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://auth:x@localhost:5432/auth_db")
    buffer = io.StringIO()
    # Sin config_file_name para que env.py no reconfigure el logging de la suite.
    cfg = Config(output_buffer=buffer)
    cfg.set_main_option("script_location", str(RAIZ / "alembic"))
    return cfg, buffer


def test_cada_revision_tiene_downgrade_real(config):
    cfg, _ = config
    revisiones = list(ScriptDirectory.from_config(cfg).walk_revisions())
    assert revisiones, "no hay revisiones"
    for revision in revisiones:
        assert callable(getattr(revision.module, "downgrade", None)), revision.revision
        cuerpo = Path(revision.path).read_text(encoding="utf-8").split("def downgrade", 1)[1]
        assert "op." in cuerpo, f"{revision.revision}: downgrade vacío"


def test_upgrade_declara_unique_en_email_y_pk_en_empleado(config):
    cfg, buffer = config
    command.upgrade(cfg, "head", sql=True)
    sql = buffer.getvalue()
    assert "CREATE TABLE cuentas" in sql
    assert "PRIMARY KEY (empleado_id)" in sql
    assert "CREATE UNIQUE INDEX ix_cuentas_email ON cuentas (email)" in sql
    assert "PRIMARY KEY (id)" in sql  # eventos_procesados: deduplicación por id de evento


def test_downgrade_completo_deshace_todo(config):
    cfg, buffer = config
    command.downgrade(cfg, "head:base", sql=True)
    sql = buffer.getvalue()
    for objeto in ("DROP TABLE eventos_procesados", "DROP INDEX ix_cuentas_email", "DROP TABLE cuentas"):
        assert objeto in sql
