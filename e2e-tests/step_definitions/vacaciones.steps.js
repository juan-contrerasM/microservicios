import assert from 'node:assert/strict';
import { Given, Then, When } from '@cucumber/cucumber';
import { esperarNotificacion, fechaUtcMasDias } from '../support/fabrica.js';
import { pedir } from '../support/http.js';
import { eventualmente } from '../support/polling.js';

function periodoFuturo() {
  return { fechaInicio: fechaUtcMasDias(1), fechaFin: fechaUtcMasDias(3) };
}

async function programar(world, fechas = periodoFuturo()) {
  world.periodoVacaciones = fechas;
  return pedir(world, 'POST', '/vacaciones', {
    body: { empleadoId: world.empleado.id, ...fechas },
  });
}

Given('que el empleado tiene vacaciones futuras programadas', async function () {
  const respuesta = await programar(this);
  assert.equal(respuesta.status, 201, JSON.stringify(respuesta.cuerpo));
  this.vacacion = respuesta.cuerpo;
});

When('programo vacaciones futuras para el empleado de prueba', async function () {
  const respuesta = await programar(this);
  if (respuesta.status === 201) {
    this.vacacion = respuesta.cuerpo;
  }
});

When('intento programar vacaciones con fecha fin anterior al inicio', async function () {
  await programar(this, { fechaInicio: fechaUtcMasDias(3), fechaFin: fechaUtcMasDias(1) });
});

When('intento programar otro período que se solapa', async function () {
  await programar(this, { fechaInicio: fechaUtcMasDias(2), fechaFin: fechaUtcMasDias(4) });
});

When('fuerzo el inicio de las vacaciones', async function () {
  await pedir(this, 'POST', `/vacaciones/${encodeURIComponent(this.vacacion.id)}/forzar-inicio`);
});

When('fuerzo el fin de las vacaciones', async function () {
  await pedir(this, 'POST', `/vacaciones/${encodeURIComponent(this.vacacion.id)}/forzar-fin`);
});

Then('el período queda en estado {string}', function (estado) {
  assert.equal(this.ultimaRespuesta?.cuerpo?.estado, estado);
});

Then('eventualmente recibe una notificación {string} que contiene {string}', async function (tipo, fragmento) {
  await esperarNotificacion(
    this,
    this.empleado.id,
    this.tokenAdmin,
    (item) => item?.tipo === tipo && item?.mensaje?.includes(fragmento),
  );
});

Then('eventualmente el empleado no puede iniciar sesión porque la cuenta no está activa', async function () {
  await eventualmente(async () => {
    const respuesta = await pedir(this, 'POST', '/auth/login', {
      auth: false,
      body: { usuario: this.empleado.email, contrasena: this.entorno.userPassword },
    });
    return respuesta.status === 401 && respuesta.cuerpo?.mensaje === 'La cuenta no está activa';
  }, {
    intentos: this.entorno.pollIntentos,
    intervaloMs: this.entorno.pollIntervaloMs,
  });
});

Then('eventualmente el empleado puede iniciar sesión nuevamente', async function () {
  await eventualmente(async () => {
    const respuesta = await pedir(this, 'POST', '/auth/login', {
      auth: false,
      body: { usuario: this.empleado.email, contrasena: this.entorno.userPassword },
    });
    if (respuesta.status === 200 && respuesta.cuerpo?.token) {
      this.tokenUsuario = respuesta.cuerpo.token;
      return true;
    }
    return false;
  }, {
    intentos: this.entorno.pollIntentos,
    intervaloMs: this.entorno.pollIntervaloMs,
  });
});
