const FECHA = /^\d{4}-\d{2}-\d{2}$/;

export function parseFecha(valor) {
	if (typeof valor !== 'string' || !FECHA.test(valor)) {
		return null;
	}
	const [anio, mes, dia] = valor.split('-').map(Number);
	const fecha = new Date(Date.UTC(anio, mes - 1, dia));
	if (fecha.getUTCFullYear() !== anio || fecha.getUTCMonth() !== mes - 1 || fecha.getUTCDate() !== dia) {
		return null;
	}
	return valor;
}

export function hoyUtc(ahora = new Date()) {
	return ahora.toISOString().slice(0, 10);
}

export function diasHabiles(inicio, fin) {
	let cuenta = 0;
	const cursor = new Date(`${inicio}T00:00:00Z`);
	const ultimo = new Date(`${fin}T00:00:00Z`);
	while (cursor <= ultimo) {
		const dia = cursor.getUTCDay();
		if (dia !== 0 && dia !== 6) {
			cuenta += 1;
		}
		cursor.setUTCDate(cursor.getUTCDate() + 1);
	}
	return cuenta;
}

export function seSolapan(inicioA, finA, inicioB, finB) {
	return inicioA <= finB && inicioB <= finA;
}
