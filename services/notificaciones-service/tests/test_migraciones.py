"""E05: toda revisión de Alembic tiene downgrade y el esquema declara la unicidad que el servicio necesita.

Sin Docker no hay PostgreSQL: se usa el modo offline de Alembic (``--sql``), que
genera el DDL de upgrade y downgrade sin conectarse.
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
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://notificaciones:x@localhost:5432/notificaciones_db")
    buffer = io.StringIO()
    # Sin config_file_name para que env.py no reconfigure el logging de la suite.
    cfg = Config(output_buffer=buffer)
    cfg.set_main_option("script_location", str(RAIZ / "alembic"))
    return cfg, buffer


def test_cada_revision_tiene_downgrade_real(config):
    cfg, _ = config
    revisiones = list(ScriptDirectory.from_config(cfg).walk_revisions())
    assert [r.revision for r in revisiones] == ["002_destinatario_email", "001_inicial"]
    for revision in revisiones:
        cuerpo = Path(revision.path).read_text(encoding="utf-8").split("def downgrade", 1)[1]
        assert "op." in cuerpo, f"{revision.revision}: downgrade vacío"


def test_upgrade_declara_claves_primarias(config):
    cfg, buffer = config
    command.upgrade(cfg, "head", sql=True)
    sql = buffer.getvalue()
    assert "PRIMARY KEY (empleado_id)" in sql  # un destinatario por empleado
    assert sql.count("PRIMARY KEY (id)") == 2  # notificaciones y eventos_procesados (deduplicación)
    # El índice por email no es UNIQUE a propósito: es una réplica y solo acelera usuario.recuperacion.
    assert "CREATE INDEX ix_destinatarios_email ON destinatarios (email)" in sql


def test_downgrade_completo_deshace_todo(config):
    cfg, buffer = config
    command.downgrade(cfg, "head:base", sql=True)
    sql = buffer.getvalue()
    for objeto in (
        "DROP INDEX ix_destinatarios_email",
        "DROP TABLE eventos_procesados",
        "DROP TABLE notificaciones",
        "DROP TABLE destinatarios",
    ):
        assert objeto in sql
