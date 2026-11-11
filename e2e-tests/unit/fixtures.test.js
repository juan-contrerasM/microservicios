import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { construirPublicacion, leerArgumentos } from '../scripts/republicar.js';

// Fase 4: los fixtures cumplen (o rompen a propósito) el contrato C1, sin levantar el sistema.

const CAMPOS = ['id', 'type', 'version', 'occurredAt', 'producer', 'data'];
const leer = (nombre) => readFileSync(new URL(`../fixtures/${nombre}`, import.meta.url), 'utf8');

for (const nombre of ['fase4-duplicado-empleado-creado.json', 'fase4-duplicado-usuario-creado.json']) {
  test(`${nombre} es un envelope C1 válido`, () => {
    const envelope = JSON.parse(leer(nombre));
    assert.deepEqual(Object.keys(envelope), CAMPOS);
    assert.equal(envelope.version, 1);
    assert.ok(!Number.isNaN(Date.parse(envelope.occurredAt)));
    assert.equal(typeof envelope.data, 'object');
  });
}

test('el fixture inválido no trae version, occurredAt ni producer', () => {
  const envelope = JSON.parse(leer('fase4-invalido-sin-campos.json'));
  for (const campo of ['version', 'occurredAt', 'producer']) {
    assert.ok(!(campo in envelope), campo);
  }
});

test('el poison no es JSON', () => {
  assert.throws(() => JSON.parse(leer('fase4-poison-no-json.txt')));
});

test('republicar usa el type como routing key y el id como message_id', () => {
  const cuerpo = construirPublicacion(leer('fase4-duplicado-empleado-creado.json'));
  assert.equal(cuerpo.routing_key, 'empleado.creado');
  assert.equal(cuerpo.properties.message_id, '6f1c2a7e-0b4d-4c55-9a3e-fa5e4d0c0001');
  assert.equal(cuerpo.properties.delivery_mode, 2);
  assert.equal(cuerpo.payload_encoding, 'string');
});

test('--id-nuevo cambia el id una sola vez y el poison exige --routing-key', () => {
  const cuerpo = construirPublicacion(leer('fase4-duplicado-usuario-creado.json'), { idNuevo: true });
  assert.notEqual(JSON.parse(cuerpo.payload).id, '6f1c2a7e-0b4d-4c55-9a3e-fa5e4d0c0002');
  assert.equal(JSON.parse(cuerpo.payload).id, cuerpo.properties.message_id);
  assert.throws(() => construirPublicacion(leer('fase4-poison-no-json.txt')), /--routing-key/);
  const poison = construirPublicacion(leer('fase4-poison-no-json.txt'), { routingKey: 'empleado.creado' });
  assert.equal(poison.routing_key, 'empleado.creado');
});

test('leerArgumentos valida --veces', () => {
  assert.deepEqual(leerArgumentos(['f.json', '--veces', '2']), {
    archivo: 'f.json', veces: 2, routingKey: null, idNuevo: false,
  });
  assert.throws(() => leerArgumentos(['f.json', '--veces', '0']));
  assert.throws(() => leerArgumentos([]));
});
