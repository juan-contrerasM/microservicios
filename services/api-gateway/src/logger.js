import { trace } from '@opentelemetry/api';

const service = process.env.OTEL_SERVICE_NAME || 'api-gateway';

function serialize(value) {
	if (value instanceof Error) return { message: value.message, stack: value.stack };
	if (typeof value === 'string') return value;
	return value;
}

export function log(level, message, fields = {}) {
	const spanContext = trace.getActiveSpan()?.spanContext();
	process.stdout.write(`${JSON.stringify({
		timestamp: new Date().toISOString(),
		level,
		service,
		...(spanContext?.traceId ? { traceId: spanContext.traceId, spanId: spanContext.spanId } : {}),
		message,
		...fields,
	})}\n`);
}

export function installConsoleLogger() {
	console.log = (...args) => log('INFO', args.map(serialize).join(' '));
	console.error = (...args) => log('ERROR', args.map(serialize).join(' '));
	console.warn = (...args) => log('WARN', args.map(serialize).join(' '));
}

