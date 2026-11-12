"""Fase 11 (E20): métricas de negocio y de consumo de eventos.

Se registran en el registro por defecto de prometheus_client, el mismo que expone `/metrics`
(prometheus-fastapi-instrumentator). La etiqueta `tipo` es el tipo de evento que originó el
correo: baja cardinalidad, sin destinatarios ni ids.
"""

from prometheus_client import Counter

NOTIFICACIONES_ENVIADAS = Counter("notificaciones_enviadas", "Correos entregados al SMTP", ["tipo"])
NOTIFICACIONES_FALLIDAS = Counter("notificaciones_fallidas", "Correos que el SMTP rechazó", ["tipo"])

EVENTOS_PROCESADOS = Counter("eventos_procesados", "Eventos consumidos con efecto", ["tipo"])
EVENTOS_DUPLICADOS = Counter("eventos_duplicados", "Eventos repetidos descartados por id", ["tipo"])
EVENTOS_DLQ = Counter("eventos_dlq", "Mensajes enviados a la DLQ", ["tipo"])


def registrar_correo(exitoso: bool, tipo: str | None) -> None:
    contador = NOTIFICACIONES_ENVIADAS if exitoso else NOTIFICACIONES_FALLIDAS
    contador.labels(tipo=tipo or "desconocido").inc()


def registrar_evento(resultado: str, tipo: str | None) -> None:
    contador = {"procesados": EVENTOS_PROCESADOS, "duplicados": EVENTOS_DUPLICADOS, "dlq": EVENTOS_DLQ}[resultado]
    contador.labels(tipo=tipo or "desconocido").inc()


def inicializar_eventos(tipos) -> None:
    """Crea en 0 las series de cada tipo consumido: increase() necesita ver el 0 para detectar
    el primer mensaje que llega a la DLQ (si la serie nace en 1, la alerta no se dispara)."""
    for tipo in tipos:
        for contador in (EVENTOS_PROCESADOS, EVENTOS_DUPLICADOS, EVENTOS_DLQ):
            contador.labels(tipo=tipo)
