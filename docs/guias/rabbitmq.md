# RabbitMQ

URL: http://localhost:15672 · usuario `RABBITMQ_USER` · contraseña en `secrets/rabbitmq_password.txt`.
AMQP (5672) solo es accesible dentro de la red `messaging`.

| Cola | Consumidor | Claves |
|---|---|---|
| `q.auth` | auth | `empleado.creado`, `empleado.retirado`, `vacaciones.iniciadas`, `vacaciones.finalizadas` |
| `q.perfiles` | perfiles | `empleado.creado`, `empleado.actualizado`, `empleado.retirado` |
| `q.vacaciones` | vacaciones | `empleado.creado`, `empleado.retirado` |
| `q.notificaciones` | notificaciones | todos los eventos que generan correo |

Cada cola tiene `<cola>.retry` (TTL 5 s, vuelve a la cola) y `<cola>.dlq`. Un mensaje inválido va
directo a la DLQ; uno que falla se reintenta 3 veces y luego va a la DLQ. Grafana alerta en cuanto un
mensaje llega a una DLQ (métrica `eventos_dlq_total`). Con varias réplicas, todas consumen la misma
cola (competing consumers) y la deduplicación por `id` evita efectos dobles.

Republicar un evento para probar idempotencia: `node e2e-tests/scripts/republicar.js <fixture>
--veces 2` (fixtures en `e2e-tests/fixtures/`). Catálogo completo: [`../eventos.md`](../eventos.md).
