import test from 'node:test';
import assert from 'node:assert/strict';
import { ErrorApi } from '../src/errores.js';
import { crearTransiciones } from '../src/transiciones.js';

const HOY = '2026-10-04';

function memoria(periodos) {
	const filas = new Map(periodos.map((p) => [p.id, { ...p }]));
	const empleados = new Map([
		['E001', { empleadoId: 'E001', email: 'juan.perez@empresa.com', estado: 'ACTIVO' }],
	]);
	const pasar = (filtro, estado) => {
		const cambiados = [];
		for (const fila of filas.values()) {
			if (filtro(fila)) {
				fila.estado = estado;
				cambiados.push({ ...fila });
			}
		}
		return cambiados;
	};
	return {
		filas,
		repo: {
			obtenerEmpleado: async (_c, id) => empleados.get(id) ?? null,
			iniciarVencidos: async (_c, hoy) =>
				pasar((f) => f.estado === 'PROGRAMADA' && f.fechaInicio <= hoy, 'EN_CURSO'),
			finalizarVencidos: async (_c, hoy) =>
				pasar((f) => f.estado === 'EN_CURSO' && f.fechaFin < hoy, 'FINALIZADA'),
			bloquearPeriodo: async (_c, id) => (filas.has(id) ? { ...filas.get(id) } : null),
			cambiarEstado: async (_c, id, estado) => {
				filas.get(id).estado = estado;
				return { ...filas.get(id) };
			},
		},
	};
}

const pool = {
	connect: async () => ({ query: async () => {}, release: () => {} }),
};

function publicador() {
	const eventos = [];
	return {
		eventos,
		publicarEvento: async (tipo, data) => {
			eventos.push({ tipo, data });
		},
	};
}

function periodo(id, estado, fechaInicio, fechaFin) {
	return { id, empleadoId: 'E001', estado, fechaInicio, fechaFin, diasHabiles: 1 };
}

function armar(periodos, publicar = publicador()) {
	const { filas, repo } = memoria(periodos);
	const transiciones = crearTransiciones({
		pool,
		publicar,
		ahora: () => new Date(`${HOY}T12:00:00Z`),
		repo,
	});
	return { filas, publicar, transiciones };
}

test('un PROGRAMADA que inicia hoy pasa a EN_CURSO y publica una sola vez aunque el job corra dos veces', async () => {
	const { filas, publicar, transiciones } = armar([periodo('V-2026-0001', 'PROGRAMADA', HOY, '2026-10-09')]);

	await transiciones.ejecutarCiclo();
	await transiciones.ejecutarCiclo();

	assert.equal(filas.get('V-2026-0001').estado, 'EN_CURSO');
	assert.deepEqual(publicar.eventos, [{
		tipo: 'vacaciones.iniciadas',
		data: {
			vacacionesId: 'V-2026-0001',
			empleadoId: 'E001',
			email: 'juan.perez@empresa.com',
			fechaInicio: HOY,
			fechaFin: '2026-10-09',
		},
	}]);
});

test('un EN_CURSO cuya fechaFin es hoy no finaliza todavía', async () => {
	const { filas, publicar, transiciones } = armar([periodo('V-2026-0002', 'EN_CURSO', '2026-10-01', HOY)]);

	await transiciones.ejecutarCiclo();

	assert.equal(filas.get('V-2026-0002').estado, 'EN_CURSO');
	assert.deepEqual(publicar.eventos, []);
});

test('un EN_CURSO cuya fechaFin ya pasó finaliza y publica vacaciones.finalizadas', async () => {
	const { filas, publicar, transiciones } = armar([periodo('V-2026-0003', 'EN_CURSO', '2026-09-28', '2026-10-03')]);

	await transiciones.ejecutarCiclo();

	assert.equal(filas.get('V-2026-0003').estado, 'FINALIZADA');
	assert.deepEqual(publicar.eventos, [{
		tipo: 'vacaciones.finalizadas',
		data: {
			vacacionesId: 'V-2026-0003',
			empleadoId: 'E001',
			email: 'juan.perez@empresa.com',
			fechaFin: '2026-10-03',
		},
	}]);
});

test('un período atrasado inicia y finaliza en el mismo ciclo, en ese orden', async () => {
	const { publicar, transiciones } = armar([periodo('V-2026-0004', 'PROGRAMADA', '2026-09-20', '2026-09-25')]);

	await transiciones.ejecutarCiclo();

	assert.deepEqual(publicar.eventos.map((e) => e.tipo), ['vacaciones.iniciadas', 'vacaciones.finalizadas']);
});

test('el cron no toca un CANCELADA ni un FINALIZADA', async () => {
	const { filas, publicar, transiciones } = armar([
		periodo('V-2026-0005', 'CANCELADA', '2026-10-01', '2026-10-02'),
		periodo('V-2026-0006', 'FINALIZADA', '2026-09-01', '2026-09-02'),
	]);

	await transiciones.ejecutarCiclo();

	assert.equal(filas.get('V-2026-0005').estado, 'CANCELADA');
	assert.equal(filas.get('V-2026-0006').estado, 'FINALIZADA');
	assert.deepEqual(publicar.eventos, []);
});

test('forzar-inicio de un PROGRAMADA futuro lo deja EN_CURSO y publica vacaciones.iniciadas', async () => {
	const { publicar, transiciones } = armar([periodo('V-2026-0007', 'PROGRAMADA', '2026-10-05', '2026-10-05')]);

	const resultado = await transiciones.forzarInicio('V-2026-0007');

	assert.equal(resultado.estado, 'EN_CURSO');
	assert.equal(resultado.email, undefined);
	assert.deepEqual(publicar.eventos.map((e) => e.tipo), ['vacaciones.iniciadas']);
});

test('forzar-fin de un EN_CURSO lo deja FINALIZADA y publica vacaciones.finalizadas', async () => {
	const { publicar, transiciones } = armar([periodo('V-2026-0008', 'EN_CURSO', '2026-10-05', '2026-10-05')]);

	const resultado = await transiciones.forzarFin('V-2026-0008');

	assert.equal(resultado.estado, 'FINALIZADA');
	assert.deepEqual(publicar.eventos, [{
		tipo: 'vacaciones.finalizadas',
		data: {
			vacacionesId: 'V-2026-0008',
			empleadoId: 'E001',
			email: 'juan.perez@empresa.com',
			fechaFin: '2026-10-05',
		},
	}]);
});

test('forzar fuera de estado responde 400 y no publica; un id inexistente es 404', async () => {
	const { publicar, transiciones } = armar([periodo('V-2026-0009', 'EN_CURSO', '2026-10-05', '2026-10-05')]);

	await assert.rejects(transiciones.forzarInicio('V-2026-0009'), (error) =>
		error instanceof ErrorApi
		&& error.status === 400
		&& error.body.mensaje === 'Solo se puede forzar el inicio de un período PROGRAMADA');
	await assert.rejects(transiciones.forzarFin('NO-EXISTE'), (error) =>
		error instanceof ErrorApi && error.status === 404);
	await transiciones.forzarFin('V-2026-0009');
	await assert.rejects(transiciones.forzarFin('V-2026-0009'), (error) =>
		error.status === 400 && error.body.mensaje === 'Solo se puede forzar el fin de un período EN_CURSO');
	assert.equal(publicar.eventos.length, 1);
});

test('si el broker falla el período igual cambia de estado', async () => {
	const caido = { publicarEvento: async () => { throw new Error('broker caído'); } };
	const { filas, transiciones } = armar([periodo('V-2026-0010', 'PROGRAMADA', HOY, HOY)], caido);

	await transiciones.ejecutarCiclo();

	assert.equal(filas.get('V-2026-0010').estado, 'EN_CURSO');
});
