import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { After, Before, Given, Then, When } from '@cucumber/cucumber';
import { datosDepartamento, esperarNotificacion, sufijo } from '../support/fabrica.js';
import { pedir } from '../support/http.js';
import {
  arrancarServicio, detenerServicio, dockerDisponible, leerBroker, mensajesEnCola, publicar,
} from '../support/plataforma.js';
import { esperar, eventualmente } from '../support/polling.js';

const DLQ = ['q.auth.dlq', 'q.perfiles.dlq', 'q.notificaciones.dlq', 'q.vacaciones.dlq'];

// Los escenarios de plataforma necesitan acceso de operador. Sin él se marcan como omitidos,
// nunca como verdes: el resumen de Cucumber los cuenta aparte.
Before({ tags: '@broker' }, function () {
  this.broker = leerBroker();
  return this.broker ? undefined : 'skipped';
});

Before({ tags: '@caos' }, async function () {
  return (await dockerDisponible()) ? undefined : 'skipped';
});

After({ tags: '@caos' }, async function () {
  // Pase lo que pase en el escenario, el servicio vuelve a quedar arriba.
  if (this.servicioDetenido) await arrancarServicio(this.servicioDetenido);
});

// --- Departamentos -------------------------------------------------------------------------

When('registro un departamento nuevo', async function () {
  this.departamento = datosDepartamento();
  await pedir(this, 'POST', '/departamentos', { body: this.departamento });
});

When('registro de nuevo el mismo departamento', async function () {
  await pedir(this, 'POST', '/departamentos', { body: this.departamento });
});

When('renombro el departamento a {string}', async function (nombre) {
  await pedir(this, 'PUT', `/departamentos/${encodeURIComponent(this.departamento.id)}`, {
    body: { nombre, descripcion: this.departamento.descripcion },
  });
});

When('consulto el departamento', async function () {
  await pedir(this, 'GET', `/departamentos/${encodeURIComponent(this.departamento.id)}`);
});

When('elimino el departamento', async function () {
  await pedir(this, 'DELETE', `/departamentos/${encodeURIComponent(this.departamento.id)}`);
});

When('consulto el listado de departamentos', async function () {
  await pedir(this, 'GET', '/departamentos');
});

Then('el nombre del departamento es {string}', function (nombre) {
  assert.equal(this.ultimaRespuesta.cuerpo?.nombre, nombre);
});

// --- Eventos -------------------------------------------------------------------------------

When('se publica dos veces el mismo evento empleado.creado de un empleado nuevo', async function () {
  const id = `e${sufijo()}`;
  this.empleadoDelEvento = id;
  const envelope = JSON.stringify({
    id: randomUUID(),
    type: 'empleado.creado',
    version: 1,
    occurredAt: new Date().toISOString(),
    producer: 'empleados-service',
    data: { empleadoId: id, email: `${id}@example.com`, nombre: 'Duplicado', apellido: 'BDD', estado: 'ACTIVO' },
  });
  await publicar(this.broker, 'empleado.creado', envelope);
  await publicar(this.broker, 'empleado.creado', envelope);
});

Then('eventualmente existe exactamente una notificación {string} para ese empleado', async function (tipo) {
  await esperarNotificacion(this, this.empleadoDelEvento, this.token, (n) => n.tipo === tipo);
  // Margen para que un segundo efecto (si lo hubiera) alcance a aparecer.
  await esperar(this.entorno.pollIntervaloMs * 2);
  const { cuerpo } = await pedir(this, 'GET', `/notificaciones/${encodeURIComponent(this.empleadoDelEvento)}`);
  assert.equal(cuerpo.filter((n) => n.tipo === tipo).length, 1, `notificaciones: ${JSON.stringify(cuerpo)}`);
});

Then('existe exactamente un perfil para ese empleado', async function () {
  const perfil = await pedir(this, 'GET', `/perfiles/${encodeURIComponent(this.empleadoDelEvento)}`);
  assert.equal(perfil.status, 200);
  const { cuerpo } = await pedir(this, 'GET', '/perfiles');
  assert.equal(cuerpo.filter((p) => p.empleadoId === this.empleadoDelEvento).length, 1);
});

When('se publica un mensaje que no es JSON con la clave {string}', async function (clave) {
  this.dlqAntes = Object.fromEntries(
    await Promise.all(DLQ.map(async (cola) => [cola, await mensajesEnCola(this.broker, cola)])),
  );
  await publicar(this.broker, clave, '{esto no es json');
});

Then('eventualmente las DLQ de auth, perfiles, notificaciones y vacaciones reciben el mensaje', async function () {
  await eventualmente(async () => {
    for (const cola of DLQ) {
      if (await mensajesEnCola(this.broker, cola) <= this.dlqAntes[cola]) return false;
    }
    return true;
  }, { intentos: this.entorno.pollIntentos, intervaloMs: this.entorno.pollIntervaloMs });
});

// --- Caos y circuit breaker ----------------------------------------------------------------

async function estadoCircuito(world, servicio) {
  const respuesta = await fetch(`${world.baseUrl}/metrics`);
  const texto = await respuesta.text();
  const linea = texto.split('\n').find((l) => l.startsWith(`gateway_circuit_breaker_state{servicio="${servicio}"}`));
  return linea ? Number(linea.split(' ').pop()) : null;
}

Given('que el contenedor de {string} está detenido', async function (servicio) {
  this.servicioDetenido = servicio;
  await detenerServicio(servicio);
});

When('consulto el listado de departamentos {int} veces', async function (veces) {
  for (let i = 0; i < veces; i += 1) await pedir(this, 'GET', '/departamentos');
});

Then('el circuito del Gateway hacia {string} está abierto', async function (servicio) {
  assert.equal(await estadoCircuito(this, servicio), 2, 'se esperaba OPEN (2)');
});

When('el contenedor de {string} vuelve a arrancar', async function (servicio) {
  await arrancarServicio(servicio);
  this.servicioDetenido = null;
});

Then('eventualmente el listado de departamentos responde 200', async function () {
  // El circuito queda OPEN CB_OPEN_DURATION_MS (30 s) antes de dejar pasar la prueba HALF_OPEN.
  await eventualmente(async () => (await pedir(this, 'GET', '/departamentos')).status === 200, {
    intentos: 40, intervaloMs: 2000,
  });
});

Then('el circuito del Gateway hacia {string} está cerrado', async function (servicio) {
  assert.equal(await estadoCircuito(this, servicio), 0, 'se esperaba CLOSED (0)');
});
