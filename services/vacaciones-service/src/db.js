import pg from 'pg';

const { types } = pg;
types.setTypeParser(1082, (valor) => valor);

export function crearPool(databaseUrl) {
	return new pg.Pool({ connectionString: databaseUrl });
}

export async function baseResponde(pool) {
	try {
		await pool.query('SELECT 1');
		return true;
	} catch {
		return false;
	}
}
