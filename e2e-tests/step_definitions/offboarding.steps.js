import assert from 'node:assert/strict';
import { Given, Then, When } from '@cucumber/cucumber';
import { esperarNotificacion } from '../support/fabrica.js';
import { pedir } from '../support/http.js';
import { eventualmente } from '../support/polling.js';

Given('que el empleado tiene un token de recuperación vigente', async function () {
  assert.ok(this.tokenActivacion, 'El onboarding no dejó un token RESET_PASSWORD vigente');
  this.tokenRecuperacion = this.tokenActivacion;
});

When('el administrador retira al empleado de prueba', async function () {
  await pedir(this, 'DELETE', `/empleados/${encodeURIComponent(this.empleado.id)}`);
});

When('solicito recuperar la cuenta retirada', async function () {
  await pedir(this, 'POST', '/auth/recover-password', {
    auth: false,
    body: { email: this.empleado.email },
  });
});

When('intento cambiar la contraseña retirada con el token vigente', async function () {
  await pedir(this, 'POST', '/auth/reset-password', {
    auth: false,
    body: { token: this.tokenRecuperacion, contrasena: this.entorno.userPassword },
  });
});

When('consulto la auditoría de empleados retirados', async function () {
  await pedir(this, 'GET', '/empleados?estado=RETIRADO');
});

Then('eventualmente recibe la notificación de cuenta desactivada', async function () {
  await esperarNotificacion(
    this,
    this.empleado.id,
    this.tokenAdmin,
    (item) => item?.tipo === 'CUENTA' && item?.mensaje === 'Su cuenta fue desactivada',
  );
});

Then('eventualmente el retiro bloquea el inicio de sesión', async function () {
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

Then('la auditoría contiene al empleado retirado', function () {
  assert.ok(Array.isArray(this.ultimaRespuesta?.cuerpo));
  const encontrado = this.ultimaRespuesta.cuerpo.find((item) => item?.id === this.empleado.id);
  assert.equal(encontrado?.estado, 'RETIRADO');
  assert.ok(encontrado?.fechaRetiro, 'El empleado retirado debe tener fechaRetiro');
});

Then('el empleado queda en estado {string}', function (estado) {
  assert.equal(this.ultimaRespuesta?.cuerpo?.estado, estado);
});
