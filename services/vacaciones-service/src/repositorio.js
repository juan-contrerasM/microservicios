export function aPeriodo(fila) {
	return {
		id: fila.id,
		empleadoId: fila.empleado_id,
		fechaInicio: fila.fecha_inicio,
		fechaFin: fila.fecha_fin,
		estado: fila.estado,
		fechaCreacion: fila.fecha_creacion instanceof Date
			? fila.fecha_creacion.toISOString()
			: fila.fecha_creacion,
		diasHabiles: fila.dias_habiles,
	};
}

export async function obtenerEmpleado(client, empleadoId) {
	const resultado = await client.query(
		'SELECT empleado_id, email, estado FROM empleados_replica WHERE empleado_id = $1',
		[empleadoId],
	);
	if (resultado.rowCount === 0) {
		return null;
	}
	const fila = resultado.rows[0];
	return { empleadoId: fila.empleado_id, email: fila.email, estado: fila.estado };
}

export async function buscarConflicto(client, empleadoId, fechaInicio, fechaFin) {
	const resultado = await client.query(
		`SELECT * FROM vacaciones
		 WHERE empleado_id = $1
		   AND estado IN ('PROGRAMADA', 'EN_CURSO')
		   AND fecha_inicio <= $3
		   AND fecha_fin >= $2
		 LIMIT 1`,
		[empleadoId, fechaInicio, fechaFin],
	);
	return resultado.rowCount === 0 ? null : aPeriodo(resultado.rows[0]);
}

export async function siguienteNumero(client, anio) {
	const resultado = await client.query(
		`INSERT INTO secuencias (anio, ultimo) VALUES ($1, 1)
		 ON CONFLICT (anio) DO UPDATE SET ultimo = secuencias.ultimo + 1
		 RETURNING ultimo`,
		[anio],
	);
	return resultado.rows[0].ultimo;
}

export async function insertarPeriodo(client, periodo) {
	const resultado = await client.query(
		`INSERT INTO vacaciones (id, empleado_id, fecha_inicio, fecha_fin, estado, fecha_creacion, dias_habiles)
		 VALUES ($1, $2, $3, $4, 'PROGRAMADA', now(), $5)
		 RETURNING *`,
		[periodo.id, periodo.empleadoId, periodo.fechaInicio, periodo.fechaFin, periodo.diasHabiles],
	);
	return aPeriodo(resultado.rows[0]);
}

export async function obtenerPeriodo(client, id) {
	const resultado = await client.query('SELECT * FROM vacaciones WHERE id = $1', [id]);
	return resultado.rowCount === 0 ? null : aPeriodo(resultado.rows[0]);
}

export async function listarPeriodos(client, empleadoId) {
	const resultado = empleadoId
		? await client.query(
			'SELECT * FROM vacaciones WHERE empleado_id = $1 ORDER BY fecha_inicio ASC, id ASC',
			[empleadoId],
		)
		: await client.query('SELECT * FROM vacaciones ORDER BY fecha_inicio ASC, id ASC');
	return resultado.rows.map(aPeriodo);
}

export async function marcarCancelada(client, id) {
	const resultado = await client.query(
		`UPDATE vacaciones SET estado = 'CANCELADA' WHERE id = $1 RETURNING *`,
		[id],
	);
	return aPeriodo(resultado.rows[0]);
}
