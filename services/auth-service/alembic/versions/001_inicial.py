"""Cuentas y eventos procesados de auth-service."""

from alembic import op
import sqlalchemy as sa

revision = "001_inicial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "cuentas",
        sa.Column("empleado_id", sa.String(length=64), primary_key=True),
        sa.Column("email", sa.String(length=255), nullable=False),
        sa.Column("rol", sa.String(length=16), nullable=False),
        sa.Column("estado", sa.String(length=32), nullable=False),
        sa.Column("password_hash", sa.String(length=255), nullable=True),
        sa.Column("creada_en", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_cuentas_email", "cuentas", ["email"], unique=True)
    op.create_table(
        "eventos_procesados",
        sa.Column("id", sa.String(length=64), primary_key=True),
        sa.Column("procesado_en", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("eventos_procesados")
    op.drop_index("ix_cuentas_email", table_name="cuentas")
    op.drop_table("cuentas")
