import test from 'node:test';
import assert from 'node:assert/strict';
import { ErrorApi, crearServicio } from '../src/servicio.js';

const HOY = '2026-10-04';
const ACTIVO = { empleadoId: 'E001', email: 'juan.perez@empresa.com', estado: 'ACTIVO' };

// Dobles: un pool que solo anota las sentencias de transacción, un repositorio en memoria
// y un publicador que registra los eventos. Ninguno abre red, Postgres ni RabbitMQ.
function armar({ empleado = ACTIVO, conflicto = null, periodo = null, publicar } = {}) {
	const sentencias = [];
	const insertados = [];
	const pool = {
		connect: async () => ({
			query: async (sql) => { sentencias.push(sql); },
			release: () => {},
		}),
	};
	const repo = {
		obtenerEmpleado: async () => empleado,
		buscarConflicto: async () => conflicto,
		siguienteNumero: async () => 7,
		insertarPeriodo: async (_c, fila) => {
			insertados.push(fila);
			return { ...fila, estado: 'PROGRAMADA' };
		},
		obtenerPeriodo: async () => periodo,
		marcarCancelada: async () => ({ ...periodo, estado: 'CANCELADA' }),
		listarPeriodos: async () => [],
	};
	const eventos = [];
	const publicador = publicar ?? {
		publicarProgramadas: async (data) => { eventos.push(data); },
		publicarEvento: async () => {},
	};
	const servicio = crearServicio({
		pool,
		publicar: publicador,
		ahora: () => new Date(`${HOY}T12:00:00Z`),
		repo,
	});
	return { servicio, sentencias, insertados, eventos };
}

function rechazaCon(status, mensaje) {
	return (error) => error instanceof ErrorApi && error.status === status && error.body.mensaje === mensaje;
}

test('programar un período válido lo guarda con los días hábiles y publica vacaciones.programadas', async () => {
	const { servicio, sentencias, insertados, eventos } = armar();

	const periodo = await servicio.programar({ empleadoId: ' E001 ', fechaInicio: '2027-03-15', fechaFin: '2027-03-30' });

	assert.equal(periodo.id, 'V-2027-0007');
	assert.equal(insertados[0].diasHabiles, 12);
	assert.equal(insertados[0].empleadoId, 'E001');
	assert.deepEqual(sentencias, ['BEGIN', 'COMMIT']);
	assert.deepEqual(eventos, [{
		vacacionesId: 'V-2027-0007',
		empleadoId: 'E001',
		email: 'juan.perez@empresa.com',
		fechaInicio: '2027-03-15',
		fechaFin: '2027-03-30',
		diasHabiles: 12,
	}]);
});

test('un solapamiento responde 400 con el período en conflicto, hace ROLLBACK y no publica', async () => {
	const conflicto = { id: 'V-2027-0001', estado: 'PROGRAMADA', fechaInicio: '2027-03-10', fechaFin: '2027-03-20' };
	const { servicio, sentencias, insertados, eventos } = armar({ conflicto });

	await assert.rejects(
		servicio.programar({ empleadoId: 'E001', fechaInicio: '2027-03-15', fechaFin: '2027-03-30' }),
		(error) => error.status === 400 && error.body.periodoEnConflicto.id === 'V-2027-0001',
	);
	assert.deepEqual(sentencias, ['BEGIN', 'ROLLBACK']);
	assert.equal(insertados.length, 0);
	assert.equal(eventos.length, 0);
});

test('un empleado RETIRADO no puede programar vacaciones y no se publica nada', async () => {
	const { servicio, insertados, eventos } = armar({ empleado: { ...ACTIVO, estado: 'RETIRADO' } });

	await assert.rejects(
		servicio.programar({ empleadoId: 'E001', fechaInicio: '2027-03-15', fechaFin: '2027-03-30' }),
		rechazaCon(400, 'El empleado con id E001 está retirado'),
	);
	assert.equal(insertados.length, 0);
	assert.equal(eventos.length, 0);
});

test('un empleado que no está en la réplica responde 400 sin consultar conflictos', async () => {
	const { servicio, eventos } = armar({ empleado: null });

	await assert.rejects(
		servicio.programar({ empleadoId: 'E999', fechaInicio: '2027-03-15', fechaFin: '2027-03-30' }),
		rechazaCon(400, 'El empleado con id E999 no existe'),
	);
	assert.equal(eventos.length, 0);
});

test('fechas al revés o con formato inválido se rechazan antes de abrir la transacción', async () => {
	for (const body of [
		{ empleadoId: 'E001', fechaInicio: '2027-03-30', fechaFin: '2027-03-15' },
		{ empleadoId: 'E001', fechaInicio: '30/03/2027', fechaFin: '2027-04-15' },
	]) {
		const { servicio, sentencias } = armar();
		await assert.rejects(servicio.programar(body), rechazaCon(400, 'La fechaFin no puede ser anterior a la fechaInicio'));
		assert.deepEqual(sentencias, []);
	}
});

test('una fechaInicio pasada se rechaza antes de abrir la transacción', async () => {
	const { servicio, sentencias } = armar();

	await assert.rejects(
		servicio.programar({ empleadoId: 'E001', fechaInicio: '2026-10-03', fechaFin: '2026-10-10' }),
		rechazaCon(400, 'La fechaInicio no puede ser anterior a la fecha actual'),
	);
	assert.deepEqual(sentencias, []);
});

test('si el broker falla al programar, el período igual queda guardado', async () => {
	const caido = { publicarProgramadas: async () => { throw new Error('broker caído'); } };
	const { servicio, sentencias } = armar({ publicar: caido });

	const periodo = await servicio.programar({ empleadoId: 'E001', fechaInicio: '2027-03-15', fechaFin: '2027-03-16' });

	assert.equal(periodo.estado, 'PROGRAMADA');
	assert.deepEqual(sentencias, ['BEGIN', 'COMMIT']);
});

test('cancelar un PROGRAMADA futuro lo deja CANCELADA; uno que ya inició responde 400', async () => {
	const futuro = { id: 'V-2027-0001', estado: 'PROGRAMADA', fechaInicio: '2027-03-15', fechaFin: '2027-03-30' };
	const cancelado = await armar({ periodo: futuro }).servicio.cancelar('V-2027-0001');
	assert.equal(cancelado.estado, 'CANCELADA');

	const iniciado = { ...futuro, fechaInicio: HOY };
	const { servicio, sentencias } = armar({ periodo: iniciado });
	await assert.rejects(
		servicio.cancelar('V-2027-0001'),
		rechazaCon(400, 'Solo se puede cancelar un período PROGRAMADA que aún no ha iniciado'),
	);
	assert.deepEqual(sentencias, ['BEGIN', 'ROLLBACK']);
});

test('obtener un id inexistente responde 404', async () => {
	const { servicio } = armar({ periodo: null });

	await assert.rejects(
		servicio.obtener('NO-EXISTE'),
		rechazaCon(404, 'El período de vacaciones con id NO-EXISTE no existe'),
	);
	assert.deepEqual(await servicio.listar('E001'), []);
});
