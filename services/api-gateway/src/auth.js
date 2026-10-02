import jwt from 'jsonwebtoken';

const PUBLICAS = new Set([
	'POST /auth/login',
	'POST /auth/recover-password',
	'POST /auth/reset-password',
]);

export function esPublica(method, path) {
	return PUBLICAS.has(`${method} ${path}`);
}

export function leerAccessToken(token, secret) {
	const payload = jwt.verify(token, secret, { algorithms: ['HS256'] });
	if (payload.type === 'RESET_PASSWORD') {
		throw new Error('reset');
	}
	if (!payload.sub || (payload.role !== 'ADMIN' && payload.role !== 'USER')) {
		throw new Error('claims');
	}
	return payload;
}

export function autorizado(method, path, payload) {
	if (payload.role === 'ADMIN') {
		return true;
	}
	if (payload.role !== 'USER') {
		return false;
	}
	if (method === 'GET') {
		return true;
	}
	if (method === 'POST' && path === '/auth/change-password') {
		return true;
	}
	const perfil = /^\/perfiles\/([^/]+)$/.exec(path);
	if (method === 'PUT' && perfil && decodeURIComponent(perfil[1]) === payload.sub) {
		return true;
	}
	return false;
}

export function exigirAcceso(secret) {
	return (req, res, next) => {
		if (esPublica(req.method, req.path)) {
			next();
			return;
		}

		const header = req.get('authorization') || '';
		const match = /^Bearer\s+(\S+)$/i.exec(header);
		if (!match) {
			res.status(401).json({ status: 401, mensaje: 'No autenticado' });
			return;
		}

		let payload;
		try {
			payload = leerAccessToken(match[1], secret);
		} catch {
			res.status(401).json({ status: 401, mensaje: 'No autenticado' });
			return;
		}

		if (!autorizado(req.method, req.path, payload)) {
			res.status(403).json({
				status: 403,
				mensaje: 'No tiene permisos para realizar esta operación',
			});
			return;
		}

		next();
	};
}
