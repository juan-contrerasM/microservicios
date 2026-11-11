/**
 * Checks de health con tiempo máximo (contrato C2).
 *
 * Con PostgreSQL colgado (TCP sin respuesta) `pool.query('SELECT 1')` puede no resolver nunca:
 * `pg` no tiene timeout de conexión por defecto. El check se corta a los `ms` milisegundos y se
 * reporta DOWN, así `GET /health` responde en menos de 3 s pase lo que pase con la dependencia.
 */
export async function conTimeout(verificar, ms, porDefecto) {
	let temporizador;
	const vencido = new Promise((resolve) => {
		temporizador = setTimeout(() => resolve(porDefecto), ms);
	});
	try {
		return await Promise.race([
			Promise.resolve().then(verificar).catch(() => porDefecto),
			vencido,
		]);
	} finally {
		clearTimeout(temporizador);
	}
}

/** Corre cada check (`{nombre: () => boolean | Promise<boolean>}`) con su timeout y devuelve UP/DOWN. */
export async function evaluarChecks(checks, ms) {
	const nombres = Object.keys(checks);
	const resultados = await Promise.all(nombres.map((nombre) => conTimeout(checks[nombre], ms, false)));
	return Object.fromEntries(nombres.map((nombre, i) => [nombre, resultados[i] === true ? 'UP' : 'DOWN']));
}
