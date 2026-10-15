import assert from 'node:assert/strict';
import { Given, Then, When } from '@cucumber/cucumber';
import { empleadoConCredenciales, esperarTokenActivacion } from '../support/fabrica.js';
import { pedir } from '../support/http.js';
import { alterarFirma } from '../support/tokens.js';

Given('que existe un empleado con credenciales', async function () {
  const creado = await empleadoConCredenciales(this);
  this.empleado = creado.empleado;
  this.tokenUsuario = creado.token;
  this.departamentoDelEmpleado = creado.departamento;
});

Given('un token de acceso con la firma alterada', function () {
  if (!this.tokenUsuario) {
    throw new Error('No hay un token de usuario para alterar la firma');
  }
  this.tokenAlterado = alterarFirma(this.tokenUsuario);
});

Then('eventualmente llega una notificación {string} con un token de activación', async function (tipo) {
  assert.equal(tipo, 'SEGURIDAD');
  this.tokenActivacion = await esperarTokenActivacion(
    this,
    this.empleadoRegistrado.id,
    this.tokenAdmin || this.token,
  );
});

When('ese empleado intenta iniciar sesión antes de activar la cuenta', async function () {
  await pedir(this, 'POST', '/auth/login', {
    auth: false,
    body: {
      usuario: this.empleadoRegistrado.email,
      contrasena: this.entorno.userPassword,
    },
  });
});

When('establece la contraseña con el token de activación', async function () {
  await pedir(this, 'POST', '/auth/reset-password', {
    auth: false,
    body: {
      token: this.tokenActivacion,
      contrasena: this.entorno.userPassword,
    },
  });
});

When('ese empleado inicia sesión', async function () {
  const respuesta = await pedir(this, 'POST', '/auth/login', {
    auth: false,
    body: {
      usuario: this.empleadoRegistrado.email,
      contrasena: this.entorno.userPassword,
    },
  });
  if (respuesta.status === 200 && respuesta.cuerpo?.token) {
    this.tokenUsuario = respuesta.cuerpo.token;
  }
});

Then('el rol de la sesión es {string}', function (rol) {
  assert.equal(this.ultimaRespuesta?.cuerpo?.role, rol);
});
