import express from 'express';

/** Orden fijo de la respuesta: el mismo de la Swagger UI agregada. */
export const SERVICIOS = Object.freeze([
	'auth-service',
	'empleados-service',
	'departamentos-service',
	'perfiles-service',
	'vacaciones-service',
	'notificaciones-service',
]);

/** Solo se reenvían los checks con valor texto (`UP`/`DOWN`): nada interno del backend sale al cliente. */
function checksDe(cuerpo) {
	if (!cuerpo || typeof cuerpo.checks !== 'object' || cuerpo.checks === null) return null;
	const checks = Object.entries(cuerpo.checks).filter(([, valor]) => typeof valor === 'string');
	return checks.length ? Object.fromEntries(checks) : null;
}

async function consultar(base, timeoutMs, fetchImpl) {
	const inicio = performance.now();
	const latencia = () => Math.round(performance.now() - inicio);
	try {
		const respuesta = await fetchImpl(`${base}/health`, {
			headers: { Accept: 'application/json' },
			signal: AbortSignal.timeout(timeoutMs),
		});
		let cuerpo = null;
		try {
			cuerpo = await respuesta.json();
		} catch {
			// 502 HTML del balanceador sin réplicas, o cuerpo vacío: cuenta como DOWN.
		}
		const arriba = respuesta.ok && cuerpo?.status === 'UP';
		const resultado = { status: arriba ? 'UP' : 'DOWN', httpStatus: respuesta.status, latencyMs: latencia(), checks: checksDe(cuerpo) };
		if (!arriba && !resultado.checks) resultado.detail = `respondió HTTP ${respuesta.status} sin health válido`;
		return resultado;
	} catch (error) {
		const detail = error.name === 'TimeoutError' ? `sin respuesta en ${timeoutMs} ms` : 'sin conexión';
		return { status: 'DOWN', httpStatus: null, latencyMs: latencia(), checks: null, detail };
	}
}

/**
 * Health agregado (bonificación 3 del plan): `GET /health/services`.
 *
 * - Separado de `GET /health`, que sigue midiendo solo el proceso del Gateway: Docker y Traefik usan
 *   ese, y un backend caído no debe sacar al borde de rotación.
 * - Consulta en paralelo el `/health` de cada backend (URL directa `<SERVICIO>_HEALTH_URL`, o la del
 *   proxy si no hay), sin circuit breaker ni reintentos y con un timeout por servicio, así la
 *   respuesta llega a tiempo aunque un servicio cuelgue.
 * - 200 `UP` si los seis están UP; 503 `DEGRADED` si alguno cae; 503 `DOWN` si caen todos.
 * - Público como `/health`. Para que no amplifique tráfico, peticiones simultáneas comparten la misma
 *   consulta y el resultado se reutiliza `cacheMs`.
 */
export function saludRouter({ backends, version, timeoutMs = 2_500, cacheMs = 2_000, fetchImpl = fetch }) {
	const router = express.Router();
	const configurados = SERVICIOS.filter((servicio) => backends[servicio]);
	let ultima = null;

	async function evaluar() {
		const resultados = await Promise.all(configurados.map((servicio) => consultar(backends[servicio], timeoutMs, fetchImpl)));
		const services = Object.fromEntries(configurados.map((servicio, i) => [servicio, resultados[i]]));
		const caidos = configurados.filter((servicio) => services[servicio].status !== 'UP');
		if (caidos.length) console.warn(`Health agregado: ${caidos.join(', ')} DOWN`);
		let status = 'UP';
		if (caidos.length) status = caidos.length === configurados.length ? 'DOWN' : 'DEGRADED';
		return {
			status,
			service: 'api-gateway',
			version,
			checks: { gateway: 'UP', ...Object.fromEntries(configurados.map((servicio) => [servicio, services[servicio].status])) },
			services,
			timestamp: new Date().toISOString(),
		};
	}

	router.get('/health/services', async (_req, res) => {
		if (!ultima || Date.now() >= ultima.hasta) {
			const entrada = { promesa: evaluar(), hasta: Number.POSITIVE_INFINITY };
			entrada.promesa.finally(() => {
				entrada.hasta = Date.now() + cacheMs;
			});
			ultima = entrada;
		}
		const cuerpo = await ultima.promesa;
		res.status(cuerpo.status === 'UP' ? 200 : 503).set('Cache-Control', 'no-store').json(cuerpo);
	});

	return router;
}
