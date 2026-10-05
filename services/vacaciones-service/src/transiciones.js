import { ErrorApi } from './errores.js';
import { hoyUtc } from './fechas.js';
import * as repositorio from './repositorio.js';

/**
 * Transiciones por tiempo: PROGRAMADA → EN_CURSO → FINALIZADA.
 * Las usan el cron y los dos POST de desarrollo. Cada cambio se confirma en la base
 * antes de publicar; si el broker falla, el período ya cambió y el error queda en el log.
 */
export function crearTransiciones({ pool, publicar, ahora = () => new Date(), repo = repositorio }) {
	async function enTransaccion(accion) {
		const client = await pool.connect();
		try {
			await client.query('BEGIN');
			const resultado = await accion(client);
			await client.query('COMMIT');
			return resultado;
		} catch (error) {
			await client.query('ROLLBACK');
			throw error;
		} finally {
			client.release();
		}
	}

	async function conEmail(client, periodos) {
		const salida = [];
		for (const periodo of periodos) {
			const empleado = await repo.obtenerEmpleado(client, periodo.empleadoId);
			salida.push({ periodo, email: empleado?.email ?? null });
		}
		return salida;
	}

	async function emitir(tipo, cambios) {
		for (const { periodo, email } of cambios) {
			const data = tipo === 'vacaciones.iniciadas'
				? {
					vacacionesId: periodo.id,
					empleadoId: periodo.empleadoId,
					email,
					fechaInicio: periodo.fechaInicio,
					fechaFin: periodo.fechaFin,
				}
				: {
					vacacionesId: periodo.id,
					empleadoId: periodo.empleadoId,
					email,
					fechaFin: periodo.fechaFin,
				};
			try {
				await publicar.publicarEvento(tipo, data);
				console.log(`Publicado ${tipo} de ${periodo.id} (${periodo.empleadoId})`);
			} catch (error) {
				console.error(`No se pudo publicar ${tipo} de ${periodo.id}; el período ya quedó ${periodo.estado}: ${error.message}`);
			}
		}
	}

	async function ejecutarCiclo() {
		const hoy = hoyUtc(ahora());
		const iniciados = await enTransaccion(async (client) =>
			conEmail(client, await repo.iniciarVencidos(client, hoy)));
		await emitir('vacaciones.iniciadas', iniciados);
		const finalizados = await enTransaccion(async (client) =>
			conEmail(client, await repo.finalizarVencidos(client, hoy)));
		await emitir('vacaciones.finalizadas', finalizados);
		return {
			iniciados: iniciados.map(({ periodo }) => periodo),
			finalizados: finalizados.map(({ periodo }) => periodo),
		};
	}

	async function forzar(id, desde, hacia, tipo, mensaje) {
		const cambio = await enTransaccion(async (client) => {
			const actual = await repo.bloquearPeriodo(client, id);
			if (!actual) {
				throw new ErrorApi(404, { mensaje: `El período de vacaciones con id ${id} no existe` });
			}
			if (actual.estado !== desde) {
				throw new ErrorApi(400, { mensaje });
			}
			const [conCorreo] = await conEmail(client, [await repo.cambiarEstado(client, id, hacia)]);
			return conCorreo;
		});
		await emitir(tipo, [cambio]);
		return cambio.periodo;
	}

	return {
		ejecutarCiclo,
		forzarInicio: (id) => forzar(
			id,
			'PROGRAMADA',
			'EN_CURSO',
			'vacaciones.iniciadas',
			'Solo se puede forzar el inicio de un período PROGRAMADA',
		),
		forzarFin: (id) => forzar(
			id,
			'EN_CURSO',
			'FINALIZADA',
			'vacaciones.finalizadas',
			'Solo se puede forzar el fin de un período EN_CURSO',
		),
	};
}
