"""Tablas de destinatarios, notificaciones y eventos procesados."""

from alembic import op
import sqlalchemy as sa

revision = "001_inicial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "destinatarios",
        sa.Column("empleado_id", sa.String(length=64), primary_key=True),
        sa.Column("email", sa.String(length=255), nullable=False),
        sa.Column("nombre", sa.String(length=255), nullable=False),
        sa.Column("apellido", sa.String(length=255), nullable=False),
    )
    op.create_table(
        "notificaciones",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("tipo", sa.String(length=32), nullable=False),
        sa.Column("destinatario", sa.String(length=255), nullable=False),
        sa.Column("mensaje", sa.Text(), nullable=False),
        sa.Column("fecha_envio", sa.DateTime(timezone=True), nullable=False),
        sa.Column("empleado_id", sa.String(length=64), nullable=False),
    )
    op.create_table(
        "eventos_procesados",
        sa.Column("id", sa.String(length=64), primary_key=True),
        sa.Column("procesado_en", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("eventos_procesados")
    op.drop_table("notificaciones")
    op.drop_table("destinatarios")
