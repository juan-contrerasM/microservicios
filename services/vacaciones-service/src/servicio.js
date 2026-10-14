import { ErrorApi } from './errores.js';
import { diasHabiles, hoyUtc, parseFecha } from './fechas.js';
import * as repositorio from './repositorio.js';
import { crearTransiciones } from './transiciones.js';
import { evaluarAlta, puedeCancelar } from './validar.js';

export { ErrorApi };

export function crearServicio({ pool, publicar, ahora = () => new Date(), repo = repositorio }) {
	const transiciones = crearTransiciones({ pool, publicar, ahora, repo });
	return {
		programar: (body) => programar(pool, publicar, ahora, repo, body),
		obtener: (id) => conCliente(pool, (client) => obtenerOFallar(repo, client, id)),
		listar: (empleadoId) => conCliente(pool, (client) => repo.listarPeriodos(client, empleadoId)),
		cancelar: (id) => cancelar(pool, ahora, repo, id),
		forzarInicio: transiciones.forzarInicio,
		forzarFin: transiciones.forzarFin,
		ejecutarCiclo: transiciones.ejecutarCiclo,
	};
}

async function programar(pool, publicar, ahora, repo, body) {
	const empleadoId = typeof body?.empleadoId === 'string' ? body.empleadoId.trim() : '';
	const fechaInicio = parseFecha(body?.fechaInicio);
	const fechaFin = parseFecha(body?.fechaFin);
	const hoy = hoyUtc(ahora());
	if (!fechaInicio || !fechaFin || fechaFin < fechaInicio) {
		throw new ErrorApi(400, { mensaje: 'La fechaFin no puede ser anterior a la fechaInicio' });
	}
	if (fechaInicio < hoy) {
		throw new ErrorApi(400, { mensaje: 'La fechaInicio no puede ser anterior a la fecha actual' });
	}

	const client = await pool.connect();
	let periodo;
	let email;
	try {
		await client.query('BEGIN');
		const empleado = empleadoId ? await repo.obtenerEmpleado(client, empleadoId) : null;
		const conflicto = empleado
			? await repo.buscarConflicto(client, empleadoId, fechaInicio, fechaFin)
			: null;
		const decision = evaluarAlta({
			empleadoId,
			fechaInicio,
			fechaFin,
			hoy,
			empleado,
			conflicto,
		});
		if (!decision.ok) {
			throw new ErrorApi(400, decision.body);
		}
		const anio = Number(fechaInicio.slice(0, 4));
		const numero = await repo.siguienteNumero(client, anio);
		periodo = await repo.insertarPeriodo(client, {
			id: `V-${anio}-${String(numero).padStart(4, '0')}`,
			empleadoId,
			fechaInicio,
			fechaFin,
			diasHabiles: diasHabiles(fechaInicio, fechaFin),
		});
		email = empleado.email;
		await client.query('COMMIT');
	} catch (error) {
		await client.query('ROLLBACK');
		throw error;
	} finally {
		client.release();
	}

	try {
		await publicar.publicarProgramadas({
			vacacionesId: periodo.id,
			empleadoId: periodo.empleadoId,
			email,
			fechaInicio: periodo.fechaInicio,
			fechaFin: periodo.fechaFin,
			diasHabiles: periodo.diasHabiles,
		});
	} catch (error) {
		console.error(`No se pudo publicar vacaciones.programadas de ${periodo.id}; el período ya quedó guardado: ${error.message}`);
	}
	return periodo;
}

async function cancelar(pool, ahora, repo, id) {
	const client = await pool.connect();
	try {
		await client.query('BEGIN');
		const periodo = await obtenerOFallar(repo, client, id);
		if (!puedeCancelar(periodo, hoyUtc(ahora()))) {
			throw new ErrorApi(400, {
				mensaje: 'Solo se puede cancelar un período PROGRAMADA que aún no ha iniciado',
			});
		}
		const cancelado = await repo.marcarCancelada(client, id);
		await client.query('COMMIT');
		return cancelado;
	} catch (error) {
		await client.query('ROLLBACK');
		throw error;
	} finally {
		client.release();
	}
}

async function obtenerOFallar(repo, client, id) {
	const periodo = await repo.obtenerPeriodo(client, id);
	if (!periodo) {
		throw new ErrorApi(404, { mensaje: `El período de vacaciones con id ${id} no existe` });
	}
	return periodo;
}

async function conCliente(pool, accion) {
	const client = await pool.connect();
	try {
		return await accion(client);
	} finally {
		client.release();
	}
}
