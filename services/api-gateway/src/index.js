import { createApp } from './app.js';
import { loadConfig } from './config.js';

const config = loadConfig();
const app = createApp(config);

const server = app.listen(config.port, () => {
	console.log(`api-gateway escuchando en :${config.port}`);
	console.log(`  EMPLEADOS_URL=${config.empleadosUrl}`);
	console.log(`  DEPARTAMENTOS_URL=${config.departamentosUrl}`);
	if (config.notificacionesUrl) {
		console.log(`  NOTIFICACIONES_URL=${config.notificacionesUrl}`);
	}
	if (config.perfilesUrl) {
		console.log(`  PERFILES_URL=${config.perfilesUrl}`);
	}
	if (config.vacacionesUrl) {
		console.log(`  VACACIONES_URL=${config.vacacionesUrl}`);
	}
});

function shutdown(signal) {
	console.log(`señal ${signal}, cerrando api-gateway`);
	server.close(() => process.exit(0));
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
