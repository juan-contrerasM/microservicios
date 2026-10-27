import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { leerEntorno } from '../support/entorno.js';
import { datosDepartamento, datosEmpleado, fechaUtcMasDias, sufijo } from '../support/fabrica.js';
import { ErrorDePolling, eventualmente } from '../support/polling.js';
import { alterarFirma, extraerToken } from '../support/tokens.js';

describe('entorno', () => {
  it('usa los defaults del plan cuando no hay variables', () => {
    const entorno = leerEntorno({});
    assert.equal(entorno.baseUrl, 'http://localhost:8080');
    assert.equal(entorno.pollIntentos, 15);
    assert.equal(entorno.pollIntervaloMs, 2000);
  });

  it('quita la barra final de BASE_URL', () => {
    const entorno = leerEntorno({ BASE_URL: 'http://localhost:8080/' });
    assert.equal(entorno.baseUrl, 'http://localhost:8080');
  });
});

describe('polling', () => {
  it('devuelve el primer valor verdadero sin seguir esperando', async () => {
    let llamadas = 0;
    const valor = await eventualmente(async () => {
      llamadas += 1;
      return llamadas === 3 ? 'listo' : null;
    }, { intentos: 5, intervaloMs: 0 });
    assert.equal(valor, 'listo');
    assert.equal(llamadas, 3);
  });

  it('dice que el evento no se procesó cuando se agota el presupuesto', async () => {
    await assert.rejects(
      () => eventualmente(async () => false, { intentos: 2, intervaloMs: 0 }),
      /El evento no se procesó tras 2 intentos × 0 ms/,
    );
  });

  it('no reintenta un error fatal', async () => {
    let llamadas = 0;
    await assert.rejects(
      () => eventualmente(async () => {
        llamadas += 1;
        throw new ErrorDePolling('GET respondió 401');
      }, { intentos: 5, intervaloMs: 0 }),
      /GET respondió 401/,
    );
    assert.equal(llamadas, 1);
  });
});

describe('tokens', () => {
  it('extrae el token del enlace de seguridad', () => {
    const mensaje = 'Para establecer o restablecer su contraseña ingrese a https://app.empresa.com/reset?token=abc.def.ghi';
    assert.equal(extraerToken(mensaje), 'abc.def.ghi');
  });

  it('altera solo la firma y conserva el resto del JWT', () => {
    const original = 'aaaa.bbbb.cccc';
    const alterado = alterarFirma(original);
    assert.notEqual(alterado, original);
    assert.equal(alterado.split('.').slice(0, 2).join('.'), 'aaaa.bbbb');
    assert.equal(alterado.split('.').length, 3);
  });
});

describe('fabrica', () => {
  it('genera ids que caben en las columnas y no se repiten', () => {
    const departamento = datosDepartamento();
    const empleado = datosEmpleado(departamento.id);
    assert.ok(departamento.id.length <= 50);
    assert.ok(empleado.id.length <= 64);
    assert.notEqual(sufijo(), sufijo());
    assert.match(empleado.email, /@example\.com$/);
  });

  it('genera fechas UTC relativas en formato ISO', () => {
    assert.match(fechaUtcMasDias(1), /^\d{4}-\d{2}-\d{2}$/);
    assert.ok(fechaUtcMasDias(3) > fechaUtcMasDias(1));
  });
});
