import assert from 'node:assert/strict';
import { Given, Then, When } from '@cucumber/cucumber';
import { crearDepartamento, datosEmpleado, sufijo } from '../support/fabrica.js';
import { pedir } from '../support/http.js';
import { ErrorDePolling, eventualmente } from '../support/polling.js';

Given('que existe un departamento de prueba', async function () {
  this.departamento = await crearDepartamento(this, this.tokenAdmin || this.token);
});

When('registro un empleado nuevo', async function () {
  this.empleadoRegistrado = datosEmpleado(this.departamento.id);
  await pedir(this, 'POST', '/empleados', { body: this.empleadoRegistrado });
});

When('que registro otro empleado', async function () {
  this.otroEmpleado = datosEmpleado(this.departamento.id);
  const respuesta = await pedir(this, 'POST', '/empleados', { body: this.otroEmpleado });
  if (respuesta.status !== 201) {
    throw new Error(
      `No se pudo registrar el otro empleado: ${respuesta.status} ${JSON.stringify(respuesta.cuerpo)}`,
    );
  }
});

When('consulto ese empleado', async function () {
  await pedir(this, 'GET', `/empleados/${encodeURIComponent(this.empleadoRegistrado.id)}`);
});

When('retiro al empleado de prueba', async function () {
  await pedir(this, 'DELETE', `/empleados/${encodeURIComponent(this.empleado.id)}`);
});

When('intento registrar un empleado con {string}', async function (caso) {
  const datos = datosEmpleado(this.departamento.id);
  if (caso === 'departamento inexistente') {
    datos.departamentoId = `no${sufijo()}`;
  } else if (caso === 'campo obligatorio faltante') {
    delete datos.nombre;
  } else if (caso === 'email mal formado') {
    datos.email = 'sin-arroba';
  } else if (caso === 'email duplicado') {
    const primero = datosEmpleado(this.departamento.id);
    const alta = await pedir(this, 'POST', '/empleados', { body: primero });
    if (alta.status !== 201) {
      throw new Error(
        `No se pudo crear el empleado que duplica el email: ${alta.status} ${JSON.stringify(alta.cuerpo)}`,
      );
    }
    datos.email = primero.email;
  } else {
    throw new Error(`Caso no reconocido: ${caso}`);
  }
  await pedir(this, 'POST', '/empleados', { body: datos });
});

Given('que el perfil del empleado de prueba ya existe', async function () {
  const ruta = `/perfiles/${encodeURIComponent(this.empleado.id)}`;
  await eventualmente(async () => {
    const respuesta = await pedir(this, 'GET', ruta);
    if (respuesta.status === 200) {
      return true;
    }
    if (respuesta.status === 404) {
      return false;
    }
    throw new ErrorDePolling(`GET ${ruta} respondió ${respuesta.status} ${JSON.stringify(respuesta.cuerpo)}`);
  }, {
    intentos: this.entorno.pollIntentos,
    intervaloMs: this.entorno.pollIntervaloMs,
  });
});

When('edito el perfil del empleado de prueba', async function () {
  await pedir(this, 'PUT', `/perfiles/${encodeURIComponent(this.empleado.id)}`, {
    body: { telefono: '3001112233' },
  });
});

When('edito el perfil del otro empleado', async function () {
  await pedir(this, 'PUT', `/perfiles/${encodeURIComponent(this.otroEmpleado.id)}`, {
    body: { telefono: '3001112233' },
  });
});

Then('la respuesta corresponde al empleado registrado', function () {
  assert.equal(this.ultimaRespuesta?.cuerpo?.id, this.empleadoRegistrado.id);
});

Then('el teléfono del perfil es {string}', function (telefono) {
  assert.equal(this.ultimaRespuesta?.cuerpo?.telefono, telefono);
});
