import { trace } from '@opentelemetry/api';

const service = process.env.OTEL_SERVICE_NAME || 'vacaciones-service';

export function log(level, message, fields = {}) {
	const spanContext = trace.getActiveSpan()?.spanContext();
	process.stdout.write(`${JSON.stringify({
		timestamp: new Date().toISOString(), level, service,
		...(spanContext?.traceId ? { traceId: spanContext.traceId, spanId: spanContext.spanId } : {}),
		message, ...fields,
	})}\n`);
}

export function installConsoleLogger() {
	const format = (value) => value instanceof Error ? value.message : typeof value === 'string' ? value : JSON.stringify(value);
	console.log = (...args) => log('INFO', args.map(format).join(' '));
	console.error = (...args) => log('ERROR', args.map(format).join(' '));
	console.warn = (...args) => log('WARN', args.map(format).join(' '));
}

