import { seSolapan } from './fechas.js';

export function evaluarAlta({ empleadoId, fechaInicio, fechaFin, hoy, empleado, conflicto }) {
	if (!fechaInicio || !fechaFin || fechaFin < fechaInicio) {
		return fallo('La fechaFin no puede ser anterior a la fechaInicio');
	}
	if (fechaInicio < hoy) {
		return fallo('La fechaInicio no puede ser anterior a la fecha actual');
	}
	if (!empleado) {
		return fallo(`El empleado con id ${empleadoId} no existe`);
	}
	if (empleado.estado === 'RETIRADO') {
		return fallo(`El empleado con id ${empleadoId} está retirado`);
	}
	if (conflicto) {
		return {
			ok: false,
			body: {
				mensaje: 'El empleado ya tiene un período que se solapa con las fechas solicitadas',
				periodoEnConflicto: conflicto,
			},
		};
	}
	return { ok: true };
}

export function puedeCancelar(periodo, hoy) {
	return periodo.estado === 'PROGRAMADA' && periodo.fechaInicio > hoy;
}

export function periodoQueBloquea(periodos, inicio, fin) {
	return periodos.find((periodo) =>
		(periodo.estado === 'PROGRAMADA' || periodo.estado === 'EN_CURSO')
		&& seSolapan(inicio, fin, periodo.fechaInicio, periodo.fechaFin)) ?? null;
}

function fallo(mensaje) {
	return { ok: false, body: { mensaje } };
}
