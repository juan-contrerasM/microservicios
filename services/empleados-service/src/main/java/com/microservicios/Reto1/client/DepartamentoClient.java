package com.microservicios.Reto1.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.microservicios.Reto1.exception.BadRequestException;
import com.microservicios.Reto1.exception.ServiceUnavailableException;

/**
 * Cliente síncrono para validar departamentos antes de registrar empleados.
 */
@Component
public class DepartamentoClient {

	private static final Logger log = LoggerFactory.getLogger(DepartamentoClient.class);
	private static final String UNAVAILABLE_MESSAGE =
			"El servicio de departamentos no está disponible para validar el departamento";

	private final HttpClient httpClient;
	private final String baseUrl;
	private final Duration timeout;
	private final int maxAttempts;
	private final Duration initialBackoff;
	private final Sleeper sleeper;

	@Autowired
	public DepartamentoClient(
			@Value("${departamentos.service.url}") String baseUrl,
			@Value("${departamentos.service.timeout}") Duration timeout,
			@Value("${departamentos.service.max-attempts}") int maxAttempts,
			@Value("${departamentos.service.initial-backoff}") Duration initialBackoff) {
		this(HttpClient.newBuilder().connectTimeout(timeout).build(), baseUrl, timeout,
				maxAttempts, initialBackoff, duration -> Thread.sleep(duration.toMillis()));
	}

	DepartamentoClient(HttpClient httpClient, String baseUrl, Duration timeout,
			int maxAttempts, Duration initialBackoff, Sleeper sleeper) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts debe ser mayor o igual a 1");
		}
		if (timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("timeout debe ser mayor que cero");
		}
		if (initialBackoff.isNegative()) {
			throw new IllegalArgumentException("initialBackoff no puede ser negativo");
		}

		this.httpClient = httpClient;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.timeout = timeout;
		this.maxAttempts = maxAttempts;
		this.initialBackoff = initialBackoff;
		this.sleeper = sleeper;
	}

	/**
	 * Confirma que el departamento exista. Los errores de transporte y respuestas
	 * 5xx se reintentan con backoff exponencial; un 404 se considera definitivo.
	 */
	public void validarExistencia(String departamentoId) {
		URI uri = UriComponentsBuilder.fromUriString(baseUrl)
				.pathSegment("departamentos", departamentoId)
				.build()
				.encode()
				.toUri();
		Duration backoff = initialBackoff;
		Throwable lastFailure = null;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				HttpRequest request = HttpRequest.newBuilder(uri)
						.timeout(timeout)
						.GET()
						.build();
				HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
				int statusCode = response.statusCode();

				if (statusCode >= 200 && statusCode < 300) {
					return;
				}
				if (statusCode == 404) {
					throw new BadRequestException("El departamento con id " + departamentoId + " no existe");
				}
				if (statusCode >= 400 && statusCode < 500) {
					throw new BadRequestException("No fue posible validar el departamento " + departamentoId);
				}

				lastFailure = new IOException("El servicio de departamentos respondió " + statusCode);
				log.warn("Fallo validando departamento {} (intento {}/{}): HTTP {}",
						departamentoId, attempt, maxAttempts, statusCode);
			} catch (BadRequestException ex) {
				throw ex;
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE, ex);
			} catch (IOException ex) {
				lastFailure = ex;
				log.warn("Fallo validando departamento {} (intento {}/{}): {}",
						departamentoId, attempt, maxAttempts, ex.getMessage());
			}

			if (attempt < maxAttempts) {
				esperar(backoff);
				backoff = backoff.multipliedBy(2);
			}
		}

		throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE, lastFailure);
	}

	private void esperar(Duration duration) {
		try {
			sleeper.sleep(duration);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE, ex);
		}
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}
}
