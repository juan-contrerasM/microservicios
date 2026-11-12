// Acceso a la plataforma para los escenarios @broker y @caos. La suite sigue hablando con el
// negocio solo por BASE_URL; esto es la "mano del operador": publicar en RabbitMQ por su API de
// administración y detener/arrancar un contenedor por la API de Docker Engine.
import { readFileSync } from 'node:fs';
import { request } from 'node:http';

function secreto(env, nombre) {
  if (env[nombre]?.trim()) return env[nombre].trim();
  if (env[`${nombre}_FILE`]?.trim()) return readFileSync(env[`${nombre}_FILE`].trim(), 'utf8').trim();
  return '';
}

export function leerBroker(env = process.env) {
  const clave = secreto(env, 'RABBITMQ_PASSWORD');
  if (!clave) return null;
  return {
    base: (env.RABBITMQ_MGMT_URL || 'http://localhost:15672').replace(/\/$/, ''),
    autorizacion: `Basic ${Buffer.from(`${env.RABBITMQ_USER || 'onboarding'}:${clave}`).toString('base64')}`,
    exchange: env.BROKER_EXCHANGE || 'onboarding.eventos',
  };
}

async function api(broker, metodo, ruta, cuerpo) {
  const respuesta = await fetch(`${broker.base}/api${ruta}`, {
    method: metodo,
    headers: { 'content-type': 'application/json', authorization: broker.autorizacion },
    body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
  });
  const texto = await respuesta.text();
  if (!respuesta.ok) throw new Error(`RabbitMQ ${metodo} ${ruta} respondió ${respuesta.status}: ${texto}`);
  return texto ? JSON.parse(texto) : null;
}

export async function publicar(broker, routingKey, payload, messageId) {
  const resultado = await api(broker, 'POST', `/exchanges/%2F/${encodeURIComponent(broker.exchange)}/publish`, {
    properties: { content_type: 'application/json', delivery_mode: 2, ...(messageId ? { message_id: messageId } : {}) },
    routing_key: routingKey,
    payload,
    payload_encoding: 'string',
  });
  if (!resultado?.routed) throw new Error(`El broker no enrutó ${routingKey} a ninguna cola`);
}

export async function mensajesEnCola(broker, cola) {
  const info = await api(broker, 'GET', `/queues/%2F/${encodeURIComponent(cola)}`);
  return Number(info.messages ?? 0) + Number(info.messages_unacknowledged ?? 0);
}

const SOCKET = process.env.DOCKER_SOCKET || '/var/run/docker.sock';

function docker(metodo, ruta) {
  return new Promise((resolver, rechazar) => {
    const req = request({ socketPath: SOCKET, path: ruta, method: metodo }, (res) => {
      let texto = '';
      res.on('data', (parte) => { texto += parte; });
      res.on('end', () => {
        if (res.statusCode >= 400) {
          rechazar(new Error(`Docker ${metodo} ${ruta} respondió ${res.statusCode}: ${texto}`));
          return;
        }
        // /_ping responde "OK" en texto plano; el resto, JSON o vacío.
        try {
          resolver(texto ? JSON.parse(texto) : null);
        } catch {
          resolver(texto);
        }
      });
    });
    req.on('error', rechazar);
    req.end();
  });
}

export async function dockerDisponible() {
  try {
    await docker('GET', '/_ping');
    return true;
  } catch {
    return false;
  }
}

/** Contenedores de un servicio de Compose, por etiqueta: funciona con cualquier proyecto y réplicas. */
async function contenedores(servicio) {
  const etiquetas = [`com.docker.compose.service=${servicio}`];
  if (process.env.COMPOSE_PROJECT) etiquetas.push(`com.docker.compose.project=${process.env.COMPOSE_PROJECT}`);
  const filtros = encodeURIComponent(JSON.stringify({ label: etiquetas }));
  const lista = await docker('GET', `/containers/json?all=1&filters=${filtros}`);
  if (lista.length === 0) throw new Error(`No hay contenedores del servicio ${servicio}`);
  return lista.map((c) => c.Id);
}

export async function detenerServicio(servicio) {
  for (const id of await contenedores(servicio)) await docker('POST', `/containers/${id}/stop?t=2`);
}

export async function arrancarServicio(servicio) {
  for (const id of await contenedores(servicio)) await docker('POST', `/containers/${id}/start`);
}
