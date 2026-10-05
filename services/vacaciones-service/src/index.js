import path from 'node:path';
import { fileURLToPath } from 'node:url';
import migrate from 'node-pg-migrate';
import { createApp } from './app.js';
import { loadConfig } from './config.js';
import { crearConsumidor } from './consumidor.js';
import { baseResponde, crearPool } from './db.js';
import { crearPublicador } from './publicar.js';
import { iniciarScheduler } from './scheduler.js';
import { crearServicio } from './servicio.js';

const config = loadConfig();
const pool = crearPool(config.databaseUrl);

await migrate({
	databaseUrl: config.databaseUrl,
	dir: path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'migrations'),
	direction: 'up',
	migrationsTable: 'pgmigrations',
	log: () => {},
});

let consumidorConectado = false;
const publicar = crearPublicador(config);
const servicio = crearServicio({ pool, publicar });
iniciarScheduler({ expresion: config.cron, ejecutarCiclo: servicio.ejecutarCiclo });
crearConsumidor({
	config,
	pool,
	alCambiar: (conectado) => {
		consumidorConectado = conectado;
	},
});

const app = createApp({
	servicio,
	estaSano: async () => consumidorConectado && await baseResponde(pool),
});

app.listen(config.port, () => {
	console.log(`vacaciones-service escuchando en :${config.port}`);
});
