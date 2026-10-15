import assert from 'node:assert/strict';
import { Given, Then, When } from '@cucumber/cucumber';
import { exigirCredenciales } from '../support/entorno.js';
import { login } from '../support/fabrica.js';
import { pedir } from '../support/http.js';

function exigirRespuesta(world) {
  assert.ok(world.ultimaRespuesta, 'No hay una respuesta guardada en el escenario');
  return world.ultimaRespuesta;
}

Given('que el sistema está desplegado y operativo', async function () {
  await pedir(this, 'GET', '/health', { auth: false });
});

Given('que estoy autenticado como {string}', async function (rol) {
  exigirCredenciales(this.entorno);
  if (rol === 'ADMIN') {
    if (!this.tokenAdmin) {
      const sesion = await login(this, this.entorno.adminUsuario, this.entorno.adminPassword);
      this.tokenAdmin = sesion.token;
    }
    this.token = this.tokenAdmin;
    return;
  }
  if (rol === 'USER') {
    if (!this.tokenUsuario) {
      throw new Error('No hay un usuario de prueba. Falta el antecedente de empleado con credenciales.');
    }
    this.token = this.tokenUsuario;
    return;
  }
  throw new Error(`Rol no reconocido: ${rol}`);
});

When('consulto el listado de empleados sin autenticación', async function () {
  await pedir(this, 'GET', '/empleados', { auth: false });
});

When('consulto el listado de empleados con el token {string}', async function (token) {
  await pedir(this, 'GET', '/empleados', { token });
});

When('consulto el listado de empleados con ese token', async function () {
  await pedir(this, 'GET', '/empleados', { token: this.tokenAlterado });
});

When('consulto el listado de empleados', async function () {
  await pedir(this, 'GET', '/empleados');
});

Then('la respuesta debe tener código {int}', function (codigo) {
  const respuesta = exigirRespuesta(this);
  assert.equal(respuesta.status, codigo, `esperado ${codigo}, obtenido ${respuesta.status}`);
});

Then('el mensaje debe ser {string}', function (esperado) {
  const respuesta = exigirRespuesta(this);
  const mensaje = respuesta.cuerpo && typeof respuesta.cuerpo === 'object' ? respuesta.cuerpo.mensaje : undefined;
  assert.equal(mensaje, esperado, `esperado ${JSON.stringify(esperado)}, obtenido ${JSON.stringify(mensaje)}`);
});

Then('el cuerpo indica {string}', function (fragmento) {
  const respuesta = exigirRespuesta(this);
  const texto = JSON.stringify(respuesta.cuerpo);
  assert.ok(
    texto.includes(fragmento),
    `el cuerpo no contiene ${JSON.stringify(fragmento)}: ${texto}`,
  );
});

Then('el cuerpo es una lista', function () {
  const respuesta = exigirRespuesta(this);
  assert.ok(Array.isArray(respuesta.cuerpo), `se esperaba una lista y llegó ${JSON.stringify(respuesta.cuerpo)}`);
});
