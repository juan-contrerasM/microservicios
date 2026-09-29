import logging
import smtplib
from email.message import EmailMessage

logger = logging.getLogger("notificaciones")


def crear_enviador(settings):
    def enviar(destino: str, asunto: str, cuerpo: str) -> None:
        if not settings.smtp_host:
            return
        mensaje = EmailMessage()
        mensaje["From"] = settings.smtp_from
        mensaje["To"] = destino
        mensaje["Subject"] = asunto
        mensaje.set_content(cuerpo)
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=5) as smtp:
            smtp.send_message(mensaje)
        logger.info("Correo enviado a %s con asunto %s", destino, asunto)

    return enviar
