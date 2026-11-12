"""Fase 11 (E20): métricas de negocio y de consumo de eventos.

Se registran en el registro por defecto de prometheus_client, el mismo que expone `/metrics`
(prometheus-fastapi-instrumentator). Etiquetas de baja cardinalidad: nunca ids ni emails.
"""

from prometheus_client import Counter

LOGINS = Counter("logins", "Intentos de login por resultado", ["resultado"])
CUENTAS_ACTIVADAS = Counter("cuentas_activadas", "Cuentas activadas o reactivadas")
CUENTAS_DESACTIVADAS = Counter("cuentas_desactivadas", "Cuentas desactivadas por tipo", ["tipo"])
RECUPERACIONES = Counter("recuperaciones_solicitadas", "Enlaces de recuperación emitidos")

EVENTOS_PROCESADOS = Counter("eventos_procesados", "Eventos consumidos con efecto", ["tipo"])
EVENTOS_DUPLICADOS = Counter("eventos_duplicados", "Eventos repetidos descartados por id", ["tipo"])
EVENTOS_DLQ = Counter("eventos_dlq", "Mensajes enviados a la DLQ", ["tipo"])


def registrar_login(exitoso: bool) -> None:
    LOGINS.labels(resultado="exito" if exitoso else "fallo").inc()


def registrar_saliente(evento) -> None:
    """Cuenta el hecho de negocio que anuncia un evento ya confirmado en la base."""
    if evento.tipo == "cuenta.activada":
        CUENTAS_ACTIVADAS.inc()
    elif evento.tipo == "cuenta.desactivada":
        CUENTAS_DESACTIVADAS.labels(tipo="permanente" if evento.data.get("permanente") else "temporal").inc()
    elif evento.tipo == "usuario.recuperacion":
        RECUPERACIONES.inc()


def registrar_evento(resultado: str, tipo: str | None) -> None:
    contador = {"procesados": EVENTOS_PROCESADOS, "duplicados": EVENTOS_DUPLICADOS, "dlq": EVENTOS_DLQ}[resultado]
    contador.labels(tipo=tipo or "desconocido").inc()


def inicializar_eventos(tipos) -> None:
    """Crea en 0 las series de cada tipo consumido: increase() necesita ver el 0 para detectar
    el primer mensaje que llega a la DLQ (si la serie nace en 1, la alerta no se dispara)."""
    for tipo in tipos:
        for contador in (EVENTOS_PROCESADOS, EVENTOS_DUPLICADOS, EVENTOS_DLQ):
            contador.labels(tipo=tipo)
