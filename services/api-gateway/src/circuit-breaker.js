export const CircuitState = Object.freeze({
	CLOSED: 'CLOSED',
	OPEN: 'OPEN',
	HALF_OPEN: 'HALF_OPEN',
});

export class CircuitOpenError extends Error {
	constructor(service) {
		super(`Circuito abierto para ${service}`);
		this.name = 'CircuitOpenError';
	}
}

/** Circuit breaker pequeno y sin estado externo: una instancia por backend. */
export class CircuitBreaker {
	constructor({ service, failureThreshold = 3, openDurationMs = 30_000, onStateChange = () => {} }) {
		if (failureThreshold < 1 || openDurationMs < 1) throw new Error('Configuracion de circuit breaker invalida');
		this.service = service;
		this.failureThreshold = failureThreshold;
		this.openDurationMs = openDurationMs;
		this.onStateChange = onStateChange;
		this.state = CircuitState.CLOSED;
		this.failures = 0;
		this.openedAt = 0;
		this.halfOpenRequest = false;
		this.onStateChange(this.service, this.state);
	}

	transition(state) {
		if (this.state === state) return;
		this.state = state;
		this.onStateChange(this.service, state);
	}

	acquire() {
		if (this.state === CircuitState.OPEN) {
			if (Date.now() - this.openedAt < this.openDurationMs) throw new CircuitOpenError(this.service);
			this.halfOpenRequest = false;
			this.transition(CircuitState.HALF_OPEN);
		}
		if (this.state === CircuitState.HALF_OPEN) {
			if (this.halfOpenRequest) throw new CircuitOpenError(this.service);
			this.halfOpenRequest = true;
		}
	}

	success() {
		this.failures = 0;
		this.halfOpenRequest = false;
		this.transition(CircuitState.CLOSED);
	}

	failure() {
		this.halfOpenRequest = false;
		this.failures += 1;
		if (this.state === CircuitState.HALF_OPEN || this.failures >= this.failureThreshold) {
			this.openedAt = Date.now();
			this.transition(CircuitState.OPEN);
		}
	}

	async execute(operation) {
		this.acquire();
		try {
			const result = await operation();
			this.success();
			return result;
		} catch (error) {
			this.failure();
			throw error;
		}
	}
}
