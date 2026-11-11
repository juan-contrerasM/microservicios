import path from 'node:path';
import { fileURLToPath } from 'node:url';
import migrate from 'node-pg-migrate';
import { createApp } from './app.js';
import { loadConfig } from './config.js';
import { crearConsumidor } from './consumidor.js';
import { baseResponde, crearPool } from './db.js';
import { crearPublicador } from './publicar.js';
import { evaluarChecks } from './salud.js';
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
	healthTimeoutMs: config.healthTimeoutMs,
	// Cada check con su propio tiempo máximo: una base colgada no arrastra al broker (C2).
	estaSano: () => evaluarChecks({
		database: () => baseResponde(pool),
		messageBroker: () => consumidorConectado,
	}, config.healthTimeoutMs),
});

app.listen(config.port, () => {
	console.log(`vacaciones-service escuchando en :${config.port}`);
});
