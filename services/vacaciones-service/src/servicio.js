import { diasHabiles, hoyUtc, parseFecha } from './fechas.js';
import {
	buscarConflicto,
	insertarPeriodo,
	listarPeriodos,
	marcarCancelada,
	obtenerEmpleado,
	obtenerPeriodo,
	siguienteNumero,
} from './repositorio.js';
import { evaluarAlta, puedeCancelar } from './validar.js';

export class ErrorApi extends Error {
	constructor(status, body) {
		super(body.mensaje);
		this.status = status;
		this.body = body;
	}
}

export function crearServicio({ pool, publicar, ahora = () => new Date() }) {
	return {
		programar: (body) => programar(pool, publicar, ahora, body),
		obtener: (id) => conCliente(pool, (client) => obtenerOFallar(client, id)),
		listar: (empleadoId) => conCliente(pool, (client) => listarPeriodos(client, empleadoId)),
		cancelar: (id) => cancelar(pool, ahora, id),
	};
}

async function programar(pool, publicar, ahora, body) {
	const empleadoId = typeof body?.empleadoId === 'string' ? body.empleadoId.trim() : '';
	const fechaInicio = parseFecha(body?.fechaInicio);
	const fechaFin = parseFecha(body?.fechaFin);
	const hoy = hoyUtc(ahora());
	if (!fechaInicio || !fechaFin || fechaFin <= fechaInicio) {
		throw new ErrorApi(400, { mensaje: 'La fechaFin debe ser posterior a la fechaInicio' });
	}
	if (fechaInicio < hoy) {
		throw new ErrorApi(400, { mensaje: 'La fechaInicio no puede ser anterior a la fecha actual' });
	}

	const client = await pool.connect();
	let periodo;
	let email;
	try {
		await client.query('BEGIN');
		const empleado = empleadoId ? await obtenerEmpleado(client, empleadoId) : null;
		const conflicto = empleado
			? await buscarConflicto(client, empleadoId, fechaInicio, fechaFin)
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
		const numero = await siguienteNumero(client, anio);
		periodo = await insertarPeriodo(client, {
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

async function cancelar(pool, ahora, id) {
	const client = await pool.connect();
	try {
		await client.query('BEGIN');
		const periodo = await obtenerOFallar(client, id);
		if (!puedeCancelar(periodo, hoyUtc(ahora()))) {
			throw new ErrorApi(400, {
				mensaje: 'Solo se puede cancelar un período PROGRAMADA que aún no ha iniciado',
			});
		}
		const cancelado = await marcarCancelada(client, id);
		await client.query('COMMIT');
		return cancelado;
	} catch (error) {
		await client.query('ROLLBACK');
		throw error;
	} finally {
		client.release();
	}
}

async function obtenerOFallar(client, id) {
	const periodo = await obtenerPeriodo(client, id);
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
