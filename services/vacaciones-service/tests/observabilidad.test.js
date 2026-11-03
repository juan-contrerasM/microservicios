import assert from 'node:assert/strict';
import test from 'node:test';
import { createApp } from '../src/app.js';

test('health detallado y metrics Prometheus son públicos', async (t) => {
	const app = createApp({
		servicio: {},
		estaSano: async () => ({ database: 'UP', messageBroker: 'UP' }),
	});
	const server = app.listen(0);
	t.after(() => new Promise((resolve) => server.close(resolve)));
	await new Promise((resolve) => server.once('listening', resolve));
	const base = `http://127.0.0.1:${server.address().port}`;

	const health = await fetch(`${base}/health`);
	assert.equal(health.status, 200);
	assert.deepEqual(await health.json(), {
		status: 'UP', service: 'vacaciones-service',
		checks: { database: 'UP', messageBroker: 'UP' },
	});

	const metrics = await fetch(`${base}/metrics`);
	assert.equal(metrics.status, 200);
	assert.match(metrics.headers.get('content-type'), /text\/plain/);
	assert.match(await metrics.text(), /vacaciones_process_cpu/);
});
