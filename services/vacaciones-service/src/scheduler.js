import cron from 'node-cron';
import { trace, SpanStatusCode } from '@opentelemetry/api';

const tracer = trace.getTracer('vacaciones-scheduler');

/**
 * Corre el ciclo de transiciones con la expresión de VACACIONES_CRON.
 * Una sola instancia: con N réplicas cada una dispararía el job (ver README, Reto 31).
 */
export function iniciarScheduler({ expresion, ejecutarCiclo }) {
	if (!cron.validate(expresion)) {
		throw new Error(`VACACIONES_CRON no es una expresión cron válida: "${expresion}"`);
	}
	const tarea = cron.schedule(expresion, async () => {
		await tracer.startActiveSpan('scheduler.vacaciones.ciclo', async (span) => {
			try {
				const { iniciados, finalizados } = await ejecutarCiclo();
				span.setAttribute('vacaciones.iniciadas', iniciados.length);
				span.setAttribute('vacaciones.finalizadas', finalizados.length);
				if (iniciados.length > 0 || finalizados.length > 0) {
					console.log(`Scheduler: ${iniciados.length} iniciado(s), ${finalizados.length} finalizado(s)`);
				}
			} catch (error) {
				span.recordException(error);
				span.setStatus({ code: SpanStatusCode.ERROR, message: error.message });
				console.error(`Scheduler: el ciclo falló; se reintenta en el próximo disparo: ${error.message}`);
			} finally {
				span.end();
			}
		});
	}, { name: 'vacaciones-scheduler', noOverlap: true, timezone: 'Etc/UTC' });
	console.log(`Scheduler de vacaciones activo con VACACIONES_CRON="${expresion}" (UTC)`);
	return tarea;
}
