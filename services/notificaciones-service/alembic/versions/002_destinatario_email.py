"""Índice por email en destinatarios: usuario.recuperacion solo trae el email."""

from alembic import op

revision = "002_destinatario_email"
down_revision = "001_inicial"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_index("ix_destinatarios_email", "destinatarios", ["email"])


def downgrade() -> None:
    op.drop_index("ix_destinatarios_email", table_name="destinatarios")
