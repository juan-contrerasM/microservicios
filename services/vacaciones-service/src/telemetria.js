import { NodeSDK } from '@opentelemetry/sdk-node';
import { ZipkinExporter } from '@opentelemetry/exporter-zipkin';
import { getNodeAutoInstrumentations } from '@opentelemetry/auto-instrumentations-node';
import { installConsoleLogger, log } from './logger.js';

installConsoleLogger();
const serviceName = process.env.OTEL_SERVICE_NAME || 'vacaciones-service';
const exporterUrl = process.env.OTEL_EXPORTER_ZIPKIN_ENDPOINT || 'http://zipkin:9411/api/v2/spans';
const sdk = new NodeSDK({
	serviceName,
	traceExporter: new ZipkinExporter({ url: exporterUrl, serviceName }),
	instrumentations: [getNodeAutoInstrumentations({
		'@opentelemetry/instrumentation-fs': { enabled: false },
	})],
});
sdk.start();
log('INFO', 'OpenTelemetry iniciado', { exporter: exporterUrl });
process.once('SIGTERM', () => sdk.shutdown().catch(() => {}));
process.once('SIGINT', () => sdk.shutdown().catch(() => {}));

