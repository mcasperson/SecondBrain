package secondbrain.domain.httpclient;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TryHttpClientCalled}. JAX-RS clients and responses are Mockito mocks so that no network is
 * used and closing can be observed.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(TryHttpClientCalled.class)
class TryHttpClientCalledTest {
	private final List<Client> clients = new ArrayList<>();
	private final List<Client> calledClients = new ArrayList<>();
	private final List<Response> responses = new ArrayList<>();

	@Inject
	private HttpClientCaller caller;

	private Client buildClient() {
		final Client client = mock(Client.class);
		clients.add(client);
		return client;
	}

	private Response callClient(final Client client) {
		calledClients.add(client);
		final Response response = mock(Response.class);
		when(response.getStatus()).thenReturn(201);
		responses.add(response);
		return response;
	}

	@Test
	void returnsTheValueProducedFromTheResponseOfTheBuiltClient() {
		final String result = caller.call(
				this::buildClient,
				this::callClient,
				response -> "status " + response.getStatus(),
				RuntimeException::new);

		assertEquals("status 201", result);
		assertEquals(1, clients.size());
		assertEquals(List.of(clients.getFirst()), calledClients);
	}

	@Test
	void closesTheClientAndResponseAfterASuccessfulCall() {
		caller.call(this::buildClient, this::callClient, response -> "ok", RuntimeException::new);

		verify(clients.getFirst()).close();
		verify(responses.getFirst()).close();
	}

	@Test
	void aResponseHandlingFailureIsWrappedByTheExceptionBuilderAndResourcesAreClosed() {
		final IllegalStateException parseFailure = new IllegalStateException("bad body");

		final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> caller.call(
				this::buildClient,
				this::callClient,
				response -> {
					throw parseFailure;
				},
				cause -> new IllegalArgumentException("call failed", cause)));

		assertEquals("call failed", thrown.getMessage());
		assertSame(parseFailure, thrown.getCause());
		verify(clients.getFirst()).close();
		verify(responses.getFirst()).close();
	}

	@Test
	void aCallFailureIsWrappedByTheExceptionBuilderAndTheClientIsClosed() {
		final IllegalStateException callFailure = new IllegalStateException("connection refused");
		final List<Response> handled = new ArrayList<>();

		final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> caller.call(
				this::buildClient,
				client -> {
					throw callFailure;
				},
				response -> {
					handled.add(response);
					return "ok";
				},
				cause -> new IllegalArgumentException("call failed", cause)));

		assertEquals("call failed", thrown.getMessage());
		assertSame(callFailure, thrown.getCause());
		assertTrue(handled.isEmpty());
		verify(clients.getFirst()).close();
	}

	@Test
	void aClientThatCanNotBeBuiltIsWrappedByTheExceptionBuilder() {
		final IllegalStateException buildFailure = new IllegalStateException("no client");

		final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> caller.call(
				() -> {
					throw buildFailure;
				},
				this::callClient,
				response -> "ok",
				cause -> new IllegalArgumentException("call failed", cause)));

		assertEquals("call failed", thrown.getMessage());
		assertSame(buildFailure, thrown.getCause());
		assertTrue(calledClients.isEmpty());
		assertTrue(responses.isEmpty());
	}
}
