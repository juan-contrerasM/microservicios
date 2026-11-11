import jwt from 'jsonwebtoken';
import { enviarError } from './errors.js';

const NO_AUTENTICADO = 'No autenticado';
const SIN_PERMISO = 'No tiene permisos para realizar esta operación';
// RFC 6750: un 401 de API Bearer indica el esquema esperado.
const DESAFIO = { 'WWW-Authenticate': 'Bearer' };

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
			enviarError(req, res, 401, NO_AUTENTICADO, DESAFIO);
			return;
		}

		let payload;
		try {
			payload = leerAccessToken(match[1], secret);
		} catch {
			enviarError(req, res, 401, NO_AUTENTICADO, DESAFIO);
			return;
		}

		if (!autorizado(req.method, req.path, payload)) {
			enviarError(req, res, 403, SIN_PERMISO);
			return;
		}

		next();
	};
}
