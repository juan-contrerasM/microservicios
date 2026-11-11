// Fase 4: republica un fixture en el exchange de eventos por la API de administración de
// RabbitMQ (puerto 15672), sin dependencias nuevas. Sirve para comprobar a mano que un evento
// duplicado no repite efectos y que un mensaje inválido termina en las DLQ.
//
//   node scripts/republicar.js fixtures/fase4-duplicado-empleado-creado.json --veces 2
//   node scripts/republicar.js fixtures/fase4-poison-no-json.txt --routing-key empleado.creado
//
// Variables: RABBITMQ_MGMT_URL (default http://localhost:15672), RABBITMQ_USER (default
// onboarding), RABBITMQ_PASSWORD o RABBITMQ_PASSWORD_FILE, BROKER_EXCHANGE (default
// onboarding.eventos), RABBITMQ_VHOST (default /).
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export function leerArgumentos(argv) {
  const opciones = { archivo: null, veces: 1, routingKey: null, idNuevo: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--veces') {
      opciones.veces = Number(argv[++i]);
    } else if (arg === '--routing-key') {
      opciones.routingKey = argv[++i];
    } else if (arg === '--id-nuevo') {
      opciones.idNuevo = true;
    } else if (!opciones.archivo) {
      opciones.archivo = arg;
    } else {
      throw new Error(`Argumento desconocido: ${arg}`);
    }
  }
  if (!opciones.archivo) {
    throw new Error('Uso: node scripts/republicar.js <fixture> [--veces N] [--routing-key clave] [--id-nuevo]');
  }
  if (!Number.isInteger(opciones.veces) || opciones.veces < 1) {
    throw new Error('--veces debe ser un entero mayor o igual que 1');
  }
  return opciones;
}

/**
 * Arma el cuerpo de POST /api/exchanges/{vhost}/{exchange}/publish. Si el contenido es un
 * envelope JSON, la routing key por defecto es su `type` y `message_id` es su `id`. Con
 * `idNuevo`, el id se reemplaza UNA vez: todas las repeticiones comparten el mismo id nuevo.
 */
export function construirPublicacion(contenido, { routingKey = null, idNuevo = false } = {}) {
  let payload = contenido;
  let envelope = null;
  try {
    envelope = JSON.parse(contenido);
  } catch {
    envelope = null;
  }
  if (envelope && typeof envelope === 'object' && idNuevo) {
    envelope.id = randomUUID();
    payload = JSON.stringify(envelope);
  }
  const clave = routingKey ?? envelope?.type;
  if (!clave) {
    throw new Error('El fixture no es un envelope con type: indica --routing-key');
  }
  const properties = { content_type: 'application/json', delivery_mode: 2, headers: {} };
  if (envelope?.id) {
    properties.message_id = String(envelope.id);
  }
  return { properties, routing_key: clave, payload, payload_encoding: 'string' };
}

function secreto(env, nombre) {
  if (env[nombre]?.trim()) return env[nombre].trim();
  if (env[`${nombre}_FILE`]?.trim()) return readFileSync(env[`${nombre}_FILE`].trim(), 'utf8').trim();
  return '';
}

async function main() {
  const opciones = leerArgumentos(process.argv.slice(2));
  const env = process.env;
  const base = (env.RABBITMQ_MGMT_URL || 'http://localhost:15672').replace(/\/$/, '');
  const usuario = env.RABBITMQ_USER || 'onboarding';
  const clave = secreto(env, 'RABBITMQ_PASSWORD');
  if (!clave) {
    throw new Error('Falta RABBITMQ_PASSWORD o RABBITMQ_PASSWORD_FILE');
  }
  const exchange = env.BROKER_EXCHANGE || 'onboarding.eventos';
  const vhost = encodeURIComponent(env.RABBITMQ_VHOST || '/');
  const cuerpo = construirPublicacion(readFileSync(opciones.archivo, 'utf8').trim(), opciones);
  const url = `${base}/api/exchanges/${vhost}/${encodeURIComponent(exchange)}/publish`;
  const autorizacion = `Basic ${Buffer.from(`${usuario}:${clave}`).toString('base64')}`;
  for (let i = 1; i <= opciones.veces; i += 1) {
    const respuesta = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: autorizacion },
      body: JSON.stringify(cuerpo),
    });
    const texto = await respuesta.text();
    if (!respuesta.ok) {
      throw new Error(`RabbitMQ respondió ${respuesta.status}: ${texto}`);
    }
    console.log(`${i}/${opciones.veces} ${cuerpo.routing_key} id=${cuerpo.properties.message_id ?? '-'} → ${texto}`);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
