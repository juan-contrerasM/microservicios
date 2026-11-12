# Alertas en Discord

Reglas de Grafana (`observability/grafana/provisioning/alerting/reglas.yml`): servicio caído (1 min),
5xx > 10 % (2 min), latencia p95 > 2 s (2 min), mensajes en DLQ (inmediata) y memoria > 85 % (5 min).
El contact point `equipo-microservicios` envía a **Discord** y, como respaldo local, a Mailhog.

1. En Discord: Ajustes del canal → Integraciones → Webhooks → Nuevo webhook → Copiar URL.
2. Guardar la URL como secreto (nunca en Git ni en `.env`) y recrear Grafana:

   ```bash
   printf '%s' 'https://discord.com/api/webhooks/<id>/<token>' > secrets/discord_webhook_url.txt
   docker compose up -d --force-recreate grafana
   ```

3. Probar con una alerta real: en RabbitMQ → Exchanges → `onboarding.eventos` → Publish message, con
   routing key `empleado.creado` y payload `no-es-json`. En menos de un minuto llega «Mensajes
   enviados a una DLQ» al canal y a http://localhost:8025.

Si el webhook se filtra, se elimina en Discord, se crea otro y se repite el paso 2.
