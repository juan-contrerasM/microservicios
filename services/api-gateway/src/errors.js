export function unavailablePayload(servicio) {
	const nombre = servicio.replace(/-service$/, '');
	return {
		status: 503,
		mensaje: `El servicio de ${nombre} no está disponible`,
		servicio,
	};
}

export function sendJson(res, status, payload) {
	if (!res || res.headersSent) {
		return;
	}

	const body = JSON.stringify(payload);
	res.writeHead(status, {
		'Content-Type': 'application/json; charset=utf-8',
		'Content-Length': Buffer.byteLength(body),
	});
	res.end(body);
}
