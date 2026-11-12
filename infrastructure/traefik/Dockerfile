# Fase 12: Traefik con la configuración dinámica dentro de la imagen (un bind mount no llegaría al
# daemon cuando Jenkins despliega desde su contenedor). v3.6+ negocia la API de Docker Engine 29.
FROM traefik:v3.6.1
COPY dinamico.yml /etc/traefik/dinamico.yml
