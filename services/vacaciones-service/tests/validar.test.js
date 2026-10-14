import test from 'node:test';
import assert from 'node:assert/strict';
import { diasHabiles } from '../src/fechas.js';
import { evaluarAlta, periodoQueBloquea, puedeCancelar } from '../src/validar.js';

const HOY = '2026-09-29';

test('fechaFin no puede ser anterior a fechaInicio', () => {
	const resultado = evaluarAlta({
		empleadoId: 'E001',
		fechaInicio: '2027-06-30',
		fechaFin: '2027-06-15',
		hoy: HOY,
		empleado: { estado: 'ACTIVO' },
		conflicto: null,
	});
	assert.equal(resultado.ok, false);
	assert.equal(resultado.body.mensaje, 'La fechaFin no puede ser anterior a la fechaInicio');
});

test('fechaFin igual a fechaInicio es un período de un día válido', () => {
	const resultado = evaluarAlta({
		empleadoId: 'E001',
		fechaInicio: HOY,
		fechaFin: HOY,
		hoy: HOY,
		empleado: { estado: 'ACTIVO' },
		conflicto: null,
	});
	assert.equal(resultado.ok, true);
});

test('fechaInicio no puede ser anterior a hoy', () => {
	const resultado = evaluarAlta({
		empleadoId: 'E001',
		fechaInicio: '2026-01-10',
		fechaFin: '2026-01-20',
		hoy: HOY,
		empleado: { estado: 'ACTIVO' },
		conflicto: null,
	});
	assert.equal(resultado.ok, false);
	assert.equal(resultado.body.mensaje, 'La fechaInicio no puede ser anterior a la fecha actual');
});

test('un período PROGRAMADA que se cruza bloquea e informa el conflicto', () => {
	const conflicto = {
		id: 'V-2027-0001',
		empleadoId: 'E001',
		fechaInicio: '2027-03-15',
		fechaFin: '2027-03-30',
		estado: 'PROGRAMADA',
	};
	const resultado = evaluarAlta({
		empleadoId: 'E001',
		fechaInicio: '2027-03-20',
		fechaFin: '2027-04-05',
		hoy: HOY,
		empleado: { estado: 'ACTIVO' },
		conflicto,
	});
	assert.equal(resultado.ok, false);
	assert.equal(resultado.body.periodoEnConflicto.id, 'V-2027-0001');
	assert.equal(resultado.body.periodoEnConflicto.estado, 'PROGRAMADA');
});

test('un empleado que no está en la réplica no existe', () => {
	const resultado = evaluarAlta({
		empleadoId: 'NO-EXISTE',
		fechaInicio: '2027-08-01',
		fechaFin: '2027-08-10',
		hoy: HOY,
		empleado: null,
		conflicto: null,
	});
	assert.equal(resultado.body.mensaje, 'El empleado con id NO-EXISTE no existe');
});

test('un empleado retirado no admite período', () => {
	const resultado = evaluarAlta({
		empleadoId: 'E001',
		fechaInicio: '2027-09-01',
		fechaFin: '2027-09-10',
		hoy: HOY,
		empleado: { estado: 'RETIRADO' },
		conflicto: null,
	});
	assert.equal(resultado.body.mensaje, 'El empleado con id E001 está retirado');
});

test('un período CANCELADA no bloquea el mismo rango', () => {
	const bloqueo = periodoQueBloquea([
		{
			id: 'V-2027-0001',
			estado: 'CANCELADA',
			fechaInicio: '2027-03-15',
			fechaFin: '2027-03-30',
		},
	], '2027-03-15', '2027-03-30');
	assert.equal(bloqueo, null);
});

test('cuenta los días hábiles de lunes a viernes, ambos inclusive', () => {
	assert.equal(diasHabiles('2027-03-15', '2027-03-30'), 12);
});

test('solo se cancela un PROGRAMADA cuya fechaInicio es posterior a hoy', () => {
	assert.equal(puedeCancelar({ estado: 'PROGRAMADA', fechaInicio: '2027-03-15' }, HOY), true);
	assert.equal(puedeCancelar({ estado: 'PROGRAMADA', fechaInicio: HOY }, HOY), false);
	assert.equal(puedeCancelar({ estado: 'CANCELADA', fechaInicio: '2027-03-15' }, HOY), false);
});

const ALTA_BASE = { empleadoId: 'E001', hoy: HOY, empleado: { estado: 'ACTIVO' }, conflicto: null };
const MENSAJE_ORDEN = 'La fechaFin no puede ser anterior a la fechaInicio';
const MENSAJE_PASADO = 'La fechaInicio no puede ser anterior a la fecha actual';

// Reglas de fechas de evaluarAlta: orden de las fechas y no programar en el pasado.
const FECHAS = [
	{ caso: 'fin un día antes del inicio', fechaInicio: '2027-06-15', fechaFin: '2027-06-14', mensaje: MENSAJE_ORDEN },
	{ caso: 'fin un año antes del inicio', fechaInicio: '2027-06-15', fechaFin: '2026-06-15', mensaje: MENSAJE_ORDEN },
	{ caso: 'sin fechaInicio', fechaInicio: null, fechaFin: '2027-06-15', mensaje: MENSAJE_ORDEN },
	{ caso: 'sin fechaFin', fechaInicio: '2027-06-15', fechaFin: null, mensaje: MENSAJE_ORDEN },
	{ caso: 'inicio ayer', fechaInicio: '2026-09-28', fechaFin: '2026-10-05', mensaje: MENSAJE_PASADO },
	{ caso: 'inicio y fin en el pasado', fechaInicio: '2026-01-01', fechaFin: '2026-01-02', mensaje: MENSAJE_PASADO },
	{ caso: 'inicio hoy', fechaInicio: HOY, fechaFin: '2026-10-02', mensaje: null },
	{ caso: 'inicio y fin el mismo día futuro', fechaInicio: '2027-06-15', fechaFin: '2027-06-15', mensaje: null },
	{ caso: 'fin un día después del inicio', fechaInicio: '2027-06-15', fechaFin: '2027-06-16', mensaje: null },
];

for (const { caso, fechaInicio, fechaFin, mensaje } of FECHAS) {
	test(`fechas: ${caso} ${mensaje ? 'se rechaza' : 'se acepta'}`, () => {
		const resultado = evaluarAlta({ ...ALTA_BASE, fechaInicio, fechaFin });
		assert.equal(
			resultado.body?.mensaje ?? null,
			mensaje,
			`evaluarAlta(${fechaInicio} → ${fechaFin}, hoy ${HOY}) debía ${mensaje ? `rechazar con «${mensaje}»` : 'aceptar'}`,
		);
		assert.equal(resultado.ok, mensaje === null);
	});
}

test('las fechas se validan antes que el empleado: un retirado con fechas al revés recibe el error de fechas', () => {
	const resultado = evaluarAlta({
		...ALTA_BASE,
		empleado: { estado: 'RETIRADO' },
		fechaInicio: '2027-06-15',
		fechaFin: '2027-06-01',
	});
	assert.equal(resultado.body.mensaje, MENSAJE_ORDEN);
});
