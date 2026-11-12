import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { CircuitBreaker, CircuitOpenError, CircuitState } from '../src/circuit-breaker.js';

describe('CircuitBreaker del Gateway', () => {
	it('recorre CLOSED -> OPEN -> HALF_OPEN -> CLOSED', async () => {
		const cambios = [];
		const breaker = new CircuitBreaker({
			service: 'empleados-service', failureThreshold: 2, openDurationMs: 5,
			onStateChange: (_service, state) => cambios.push(state),
		});
		const falla = () => Promise.reject(new Error('backend caido'));

		await assert.rejects(breaker.execute(falla), /backend caido/);
		assert.equal(breaker.state, CircuitState.CLOSED);
		await assert.rejects(breaker.execute(falla), /backend caido/);
		assert.equal(breaker.state, CircuitState.OPEN);
		await assert.rejects(breaker.execute(async () => 'no ejecuta'), CircuitOpenError);

		await new Promise((resolve) => setTimeout(resolve, 10));
		assert.equal(await breaker.execute(async () => 'recuperado'), 'recuperado');
		assert.equal(breaker.state, CircuitState.CLOSED);
		assert.deepEqual(cambios, ['CLOSED', 'OPEN', 'HALF_OPEN', 'CLOSED']);
	});

	it('si la prueba HALF_OPEN falla vuelve a OPEN', async () => {
		const breaker = new CircuitBreaker({ service: 'auth-service', failureThreshold: 1, openDurationMs: 1 });
		await assert.rejects(breaker.execute(async () => { throw new Error('falla'); }));
		await new Promise((resolve) => setTimeout(resolve, 2));
		await assert.rejects(breaker.execute(async () => { throw new Error('sigue caido'); }));
		assert.equal(breaker.state, CircuitState.OPEN);
	});
});
