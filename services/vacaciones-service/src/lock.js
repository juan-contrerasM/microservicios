/**
 * Fase 12 (E23): con N réplicas cada una dispara el cron. El ciclo toma un advisory lock de
 * PostgreSQL (pg_try_advisory_lock, de sesión, sin esperar): solo una réplica ejecuta las
 * transiciones de ese tick y las demás lo omiten. Si la réplica que lo tiene muere, PostgreSQL
 * libera el lock al cerrarse la conexión y el siguiente tick lo toma otra.
 */
export const LOCK_SCHEDULER = 4_207_001;

export function conLockDeScheduler(pool, ciclo, clave = LOCK_SCHEDULER) {
	return async function cicloExclusivo() {
		const client = await pool.connect();
		let tomado = false;
		try {
			const { rows } = await client.query('SELECT pg_try_advisory_lock($1) AS tomado', [clave]);
			tomado = rows[0]?.tomado === true;
			if (!tomado) {
				console.log('Scheduler: otra réplica tiene el lock; este tick se omite');
				return { iniciados: [], finalizados: [], omitido: true };
			}
			return await ciclo();
		} finally {
			if (tomado) {
				await client.query('SELECT pg_advisory_unlock($1)', [clave]).catch(() => {});
			}
			client.release();
		}
	};
}
