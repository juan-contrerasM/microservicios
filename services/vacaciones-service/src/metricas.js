import client from 'prom-client';
import { trace } from '@opentelemetry/api';
import { log } from './logger.js';

const registry = new client.Registry();
client.collectDefaultMetrics({ register: registry, prefix: 'vacaciones_' });
const requests = new client.Counter({
	name: 'http_requests_total', help: 'Peticiones HTTP recibidas',
	labelNames: ['service', 'method', 'route', 'status'], registers: [registry],
});
const duration = new client.Histogram({
	name: 'http_request_duration_seconds', help: 'Duración HTTP',
	labelNames: ['service', 'method', 'route', 'status'],
	buckets: [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10], registers: [registry],
});

export function metricsMiddleware(req, res, next) {
	if (req.path === '/metrics') return next();
	const end = duration.startTimer();
	const spanContext = trace.getActiveSpan()?.spanContext();
	const started = process.hrtime.bigint();
	res.on('finish', () => {
		const route = req.route?.path || `/${req.path.split('/').filter(Boolean).slice(0, 2).join('/')}`;
		const labels = { service: 'vacaciones-service', method: req.method, route, status: String(res.statusCode) };
		requests.inc(labels); end(labels);
		log('INFO', 'petición HTTP', {
			...labels, durationMs: Number(process.hrtime.bigint() - started) / 1e6,
			...(spanContext?.traceId ? { traceId: spanContext.traceId, spanId: spanContext.spanId } : {}),
		});
	});
	next();
}
export async function metricsHandler(_req, res) {
	res.set('Content-Type', registry.contentType);
	res.end(await registry.metrics());
}
