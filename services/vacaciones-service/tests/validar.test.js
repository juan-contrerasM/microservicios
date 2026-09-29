import test from 'node:test';
import assert from 'node:assert/strict';
import { diasHabiles } from '../src/fechas.js';
import { evaluarAlta, periodoQueBloquea, puedeCancelar } from '../src/validar.js';

const HOY = '2026-09-29';

test('fechaFin debe ser posterior a fechaInicio', () => {
	const resultado = evaluarAlta({
		empleadoId: 'E001',
		fechaInicio: '2027-06-30',
		fechaFin: '2027-06-15',
		hoy: HOY,
		empleado: { estado: 'ACTIVO' },
		conflicto: null,
	});
	assert.equal(resultado.ok, false);
	assert.equal(resultado.body.mensaje, 'La fechaFin debe ser posterior a la fechaInicio');
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
