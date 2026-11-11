import express from 'express';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import swaggerUiDist from 'swagger-ui-dist';
import { parse as parseYaml } from 'yaml';
import { enviarError } from './errors.js';

const dir = dirname(fileURLToPath(import.meta.url));
const plantilla = readFileSync(join(dir, 'swagger.html'), 'utf8');

/**
 * Ruta del contrato dentro de cada backend. Se puede cambiar por variable de entorno
 * (`<SERVICIO>_OPENAPI_PATH`) sin tocar `docker-compose.yml`: el valor por defecto es el que hoy
 * sirve cada servicio.
 */
export const RUTAS_OPENAPI_POR_DEFECTO = Object.freeze({
	'auth-service': '/openapi.json', // FastAPI
	'empleados-service': '/v3/api-docs', // Springdoc
	'departamentos-service': '/openapi.yaml', // chi, YAML embebido
	'perfiles-service': '/v3/api-docs', // Springdoc
	'vacaciones-service': '/openapi.json', // Express, src/openapi.json
	'notificaciones-service': '/openapi.json', // FastAPI
});

/** Orden del selector de la UI: primero el contrato del borde, luego los seis servicios. */
const ORDEN = ['api-gateway', ...Object.keys(RUTAS_OPENAPI_POR_DEFECTO)];

async function leerContrato(url, timeoutMs, fetchImpl) {
	const respuesta = await fetchImpl(url, {
		headers: { Accept: 'application/json, application/yaml;q=0.9, */*;q=0.1' },
		signal: AbortSignal.timeout(timeoutMs),
	});
	if (!respuesta.ok) {
		throw new Error(`HTTP ${respuesta.status}`);
	}
	const texto = await respuesta.text();
	let spec;
	try {
		spec = JSON.parse(texto);
	} catch {
		spec = parseYaml(texto); // Departamentos (Go) sirve YAML
	}
	if (!spec || typeof spec !== 'object' || !(spec.openapi || spec.swagger)) {
		throw new Error('la respuesta no es un documento OpenAPI');
	}
	return spec;
}

/**
 * Swagger UI agregada (Fase 5, paquete 5.GW).
 *
 * - `GET /swagger` sirve una sola Swagger UI con un selector de los siete contratos.
 * - `GET /swagger/specs/<servicio>` devuelve el contrato de ese servicio pidiéndoselo por HTTP a su
 *   backend con la URL que el Gateway ya usa para proxear. Si el backend no responde (caído, lento,
 *   no configurado o respuesta inválida) esa entrada responde 503 C3 y las demás siguen funcionando.
 * - El `servers` de cada contrato se reemplaza por el origen del Gateway: así "Try it out" envía la
 *   petición al Gateway (con el JWT de "Authorize") y no al hostname interno del backend, que el
 *   navegador no puede resolver.
 *
 * Todo es público (sin JWT), igual que `/openapi.json`: el contrato no es un secreto y la UI lo
 * necesita antes de que el usuario tenga token.
 */
export function swaggerRouter({ propio, backends, rutasOpenapi = {}, timeoutMs = 3_000, fetchImpl = fetch }) {
	const router = express.Router();
	const rutas = { ...RUTAS_OPENAPI_POR_DEFECTO, ...rutasOpenapi };
	const entradas = ORDEN.map((nombre) => ({ name: nombre, url: `/swagger/specs/${nombre}` }));
	const html = plantilla.replace('__SPECS__', JSON.stringify(entradas));

	const ui = (_req, res) => res.type('html').send(html);
	router.get(['/swagger', '/swagger/', '/swagger/index.html'], ui);
	router.use('/swagger/ui', express.static(swaggerUiDist.getAbsoluteFSPath(), { index: false, maxAge: '1d' }));

	router.get('/swagger/specs/:servicio', async (req, res) => {
		const { servicio } = req.params;
		if (servicio === 'api-gateway') {
			res.type('application/json').send(propio);
			return;
		}
		if (!Object.hasOwn(rutas, servicio)) {
			enviarError(req, res, 404, `No hay un contrato registrado para ${servicio}`);
			return;
		}
		const base = backends[servicio];
		if (!base) {
			enviarError(req, res, 503, `El contrato de ${servicio} no está disponible: su URL no está configurada`);
			return;
		}
		try {
			const spec = await leerContrato(`${base}${rutas[servicio]}`, timeoutMs, fetchImpl);
			spec.servers = [{ url: '/', description: 'API Gateway (las rutas de negocio pasan por el Gateway con JWT)' }];
			res.status(200).json(spec);
		} catch (error) {
			console.error(`No se pudo leer el contrato de ${servicio}: ${error.name === 'TimeoutError' ? `sin respuesta en ${timeoutMs} ms` : error.message}`);
			enviarError(req, res, 503, `El contrato de ${servicio} no está disponible`);
		}
	});

	return router;
}
