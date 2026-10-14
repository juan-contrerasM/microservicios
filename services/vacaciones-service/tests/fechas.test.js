import test from 'node:test';
import assert from 'node:assert/strict';
import { diasHabiles, hoyUtc, parseFecha, seSolapan } from '../src/fechas.js';
import { periodoQueBloquea } from '../src/validar.js';

// Período existente de referencia: lunes 15 a martes 30 de marzo de 2027.
const EXISTENTE = { inicio: '2027-03-15', fin: '2027-03-30' };

// Misma regla que buscarConflicto en repositorio.js: fecha_inicio <= fin AND fecha_fin >= inicio.
const SOLAPAMIENTO = [
	{ caso: 'empieza dentro del existente', inicio: '2027-03-20', fin: '2027-04-05', esperado: true },
	{ caso: 'termina dentro del existente', inicio: '2027-03-01', fin: '2027-03-20', esperado: true },
	{ caso: 'contiene al existente', inicio: '2027-03-01', fin: '2027-04-30', esperado: true },
	{ caso: 'está contenido en el existente', inicio: '2027-03-18', fin: '2027-03-22', esperado: true },
	{ caso: 'es idéntico al existente', inicio: '2027-03-15', fin: '2027-03-30', esperado: true },
	{ caso: 'empieza el último día del existente', inicio: '2027-03-30', fin: '2027-04-10', esperado: true },
	{ caso: 'termina el primer día del existente', inicio: '2027-03-01', fin: '2027-03-15', esperado: true },
	{ caso: 'empieza el día siguiente al fin', inicio: '2027-03-31', fin: '2027-04-10', esperado: false },
	{ caso: 'termina el día anterior al inicio', inicio: '2027-03-01', fin: '2027-03-14', esperado: false },
	{ caso: 'está en otro mes', inicio: '2027-06-01', fin: '2027-06-10', esperado: false },
	{ caso: 'es de un solo día dentro del existente', inicio: '2027-03-22', fin: '2027-03-22', esperado: true },
];

for (const { caso, inicio, fin, esperado } of SOLAPAMIENTO) {
	test(`solapamiento: un período que ${caso} ${esperado ? 'solapa' : 'no solapa'}`, () => {
		assert.equal(seSolapan(inicio, fin, EXISTENTE.inicio, EXISTENTE.fin), esperado);
		// La relación es simétrica: da igual cuál de los dos sea el nuevo.
		assert.equal(seSolapan(EXISTENTE.inicio, EXISTENTE.fin, inicio, fin), esperado);
	});
}

const ESTADOS = [
	{ estado: 'PROGRAMADA', bloquea: true },
	{ estado: 'EN_CURSO', bloquea: true },
	{ estado: 'CANCELADA', bloquea: false },
	{ estado: 'FINALIZADA', bloquea: false },
];

for (const { estado, bloquea } of ESTADOS) {
	test(`un período ${estado} que se cruza ${bloquea ? 'bloquea' : 'no bloquea'} el alta`, () => {
		const existente = { id: 'V-2027-0001', estado, fechaInicio: EXISTENTE.inicio, fechaFin: EXISTENTE.fin };
		const bloqueo = periodoQueBloquea([existente], '2027-03-20', '2027-03-25');
		assert.equal(bloqueo?.id ?? null, bloquea ? 'V-2027-0001' : null);
	});
}

test('periodoQueBloquea devuelve el primero que bloquea e ignora los que no se cruzan', () => {
	const periodos = [
		{ id: 'V-1', estado: 'PROGRAMADA', fechaInicio: '2027-01-01', fechaFin: '2027-01-05' },
		{ id: 'V-2', estado: 'CANCELADA', fechaInicio: '2027-03-15', fechaFin: '2027-03-30' },
		{ id: 'V-3', estado: 'EN_CURSO', fechaInicio: '2027-03-25', fechaFin: '2027-04-02' },
	];
	assert.equal(periodoQueBloquea(periodos, '2027-03-20', '2027-03-26').id, 'V-3');
	assert.equal(periodoQueBloquea([], '2027-03-20', '2027-03-26'), null);
});

const DIAS_HABILES = [
	{ caso: 'un lunes solo', inicio: '2027-03-15', fin: '2027-03-15', esperado: 1 },
	{ caso: 'un sábado solo', inicio: '2027-03-20', fin: '2027-03-20', esperado: 0 },
	{ caso: 'un fin de semana completo', inicio: '2027-03-20', fin: '2027-03-21', esperado: 0 },
	{ caso: 'de viernes a lunes', inicio: '2027-03-19', fin: '2027-03-22', esperado: 2 },
	{ caso: 'una semana de lunes a domingo', inicio: '2027-03-15', fin: '2027-03-21', esperado: 5 },
	{ caso: 'dos semanas de lunes a martes', inicio: '2027-03-15', fin: '2027-03-30', esperado: 12 },
	{ caso: 'cruzando fin de mes y de año', inicio: '2027-12-30', fin: '2028-01-03', esperado: 3 },
];

for (const { caso, inicio, fin, esperado } of DIAS_HABILES) {
	test(`días hábiles: ${caso} cuenta ${esperado}`, () => {
		assert.equal(diasHabiles(inicio, fin), esperado);
	});
}

test('parseFecha acepta YYYY-MM-DD real y rechaza formatos o días inexistentes', () => {
	assert.equal(parseFecha('2027-02-28'), '2027-02-28');
	assert.equal(parseFecha('2028-02-29'), '2028-02-29');
	for (const malo of ['2027-02-29', '2027-13-01', '2027-3-15', '15/03/2027', '', null, undefined, 20270315]) {
		assert.equal(parseFecha(malo), null, `debió rechazar ${String(malo)}`);
	}
});

test('hoyUtc toma la fecha en UTC, no la local', () => {
	assert.equal(hoyUtc(new Date('2026-10-13T23:59:59Z')), '2026-10-13');
	assert.equal(hoyUtc(new Date('2026-10-13T23:30:00-05:00')), '2026-10-14');
});
