# Balanceador (Traefik) y escalado

`balanceador` (Traefik 3.6) es el único puerto de negocio publicado (`:8080`). Descubre por la API de
Docker los contenedores **de su propio proyecto Compose** que tienen `traefik.enable=true`:

| Entrypoint | Puerto | Atiende |
|---|---|---|
| `web` | 8080 (publicado) | réplicas de `api-gateway` |
| `interno` | 8000 (red backend) | servicios de negocio por los alias `empleados-lb`, `departamentos-lb`, `auth-lb`, `perfiles-lb`, `vacaciones-lb`, `notificaciones-lb` |
| `metricas` | 8082 (interno) | `/ping` y métricas Prometheus |

Por qué Traefik y no Nginx: health checks **activos** contra `/health` (Nginx OSS solo los tiene
pasivos) y descubrimiento automático de réplicas sin recargar configuración.

- Una réplica que falla su `/health` sale de rotación en ≤ 5 s; mientras tanto el middleware
  `reintento` repite la petición en otra réplica si la conexión falla.
- Sin réplicas sanas, la ruta de respaldo (`infrastructure/traefik/dinamico.yml`) responde 502 y el
  Gateway devuelve 503 C3 y abre su circuito.

## Demostración

```bash
COMPOSE_PROJECT=microservicios bash scripts/demo-escalado.sh
```

Escala los **siete** servicios a 2 réplicas, comprueba que Traefik ve 2 réplicas sanas de cada uno y
que las cinco colas de consumidores tienen 2 consumidores, envía 30 peticiones por servicio, muestra
cuántas atendió cada réplica (contador HTTP de cada contenedor), detiene una réplica de cada servicio
sin avisar y comprueba que no hay errores; al final vuelve a 1 réplica.
