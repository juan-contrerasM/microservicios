package com.microservicios.Reto1.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.microservicios.Reto1.client.DepartamentoClient.Sleeper;
import com.microservicios.Reto1.exception.BadRequestException;
import com.microservicios.Reto1.exception.ServiceUnavailableException;

@ExtendWith(MockitoExtension.class)
class DepartamentoClientTest {

	@Mock
	private HttpClient httpClient;
	@Mock
	private HttpResponse<Void> response;
	@Mock
	private Sleeper sleeper;

	private DepartamentoClient client;

	@BeforeEach
	void setUp() {
		client = new DepartamentoClient(httpClient, "http://departamentos:8081/",
				Duration.ofSeconds(3), 4, Duration.ofSeconds(1), sleeper);
	}

	@Test
	void departamentoExistenteContinuaSinReintentos() throws Exception {
		when(response.statusCode()).thenReturn(200);
		when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(response);

		client.validarExistencia("IT");

		verify(httpClient).send(any(HttpRequest.class), bodyHandler());
		verifyNoInteractions(sleeper);
	}

	@Test
	void departamentoInexistenteGenera400SinReintentos() throws Exception {
		when(response.statusCode()).thenReturn(404);
		when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(response);

		assertThatThrownBy(() -> client.validarExistencia("NO-EXISTE"))
				.isInstanceOf(BadRequestException.class)
				.hasMessage("El departamento con id NO-EXISTE no existe");
		verify(httpClient).send(any(HttpRequest.class), bodyHandler());
		verify(sleeper, never()).sleep(any());
	}

	@Test
	void erroresTemporalesSeReintentanConBackoffExponencial() throws Exception {
		when(response.statusCode()).thenReturn(500, 502, 200);
		when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(response);

		client.validarExistencia("IT");

		verify(httpClient, times(3)).send(any(HttpRequest.class), bodyHandler());
		verify(sleeper).sleep(Duration.ofSeconds(1));
		verify(sleeper).sleep(Duration.ofSeconds(2));
	}

	@Test
	void erroresDeTransporteAgotanIntentosYGeneran503() throws Exception {
		when(httpClient.send(any(HttpRequest.class), bodyHandler()))
				.thenThrow(new IOException("conexión rechazada"));

		assertThatThrownBy(() -> client.validarExistencia("IT"))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessage("El servicio de departamentos no está disponible para validar el departamento");
		verify(httpClient, times(4)).send(any(HttpRequest.class), bodyHandler());
		verify(sleeper).sleep(Duration.ofSeconds(1));
		verify(sleeper).sleep(Duration.ofSeconds(2));
		verify(sleeper).sleep(Duration.ofSeconds(4));
	}

	@Test
	void identificadorSeCodificaComoSegmentoDeRuta() throws Exception {
		when(response.statusCode()).thenReturn(200);
		when(httpClient.send(any(HttpRequest.class), bodyHandler())).thenReturn(response);
		ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);

		client.validarExistencia("TI Norte");

		verify(httpClient).send(requestCaptor.capture(), bodyHandler());
		assertThat(requestCaptor.getValue().uri().toString())
				.isEqualTo("http://departamentos:8081/departamentos/TI%20Norte");
		assertThat(requestCaptor.getValue().timeout()).contains(Duration.ofSeconds(3));
	}

	private HttpResponse.BodyHandler<Void> bodyHandler() {
		return ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any();
	}
}
