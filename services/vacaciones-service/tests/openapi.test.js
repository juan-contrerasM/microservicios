import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const spec = JSON.parse(readFileSync(
	join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'openapi.json'),
	'utf8',
));

test('OpenAPI documenta BearerAuth y mantiene health público', () => {
	assert.equal(spec.components.securitySchemes.BearerAuth.scheme, 'bearer');
	assert.deepEqual(spec.security, [{ BearerAuth: [] }]);
	assert.deepEqual(spec.paths['/health'].get.security, []);
	assert.ok(spec.paths['/vacaciones/{id}/forzar-inicio']);
	assert.ok(spec.paths['/vacaciones/{id}/forzar-fin']);
});
