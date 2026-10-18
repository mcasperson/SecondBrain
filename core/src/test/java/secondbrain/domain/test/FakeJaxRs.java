package secondbrain.domain.test;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A fake JAX-RS client built from Mockito mocks. Fluent calls return the next object in the chain, and
 * {@code get()}/{@code post()} return queued responses in order. All calls are recorded for assertions.
 */
public final class FakeJaxRs {
    public final Client client;
    public final WebTarget target;
    public final Invocation.Builder builder;
    private final Deque<Response> responses = new ArrayDeque<>();

    public FakeJaxRs(final Response... queued) {
        responses.addAll(Arrays.asList(queued));
        final Answer<Object> fluent = this::answer;
        builder = mock(Invocation.Builder.class, fluent);
        target = mock(WebTarget.class, fluent);
        client = mock(Client.class, fluent);
    }

    public FakeJaxRs enqueue(final Response response) {
        responses.add(response);
        return this;
    }

    private Object answer(final org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
        final Class<?> returnType = invocation.getMethod().getReturnType();
        if (returnType == Response.class) {
            if (responses.isEmpty()) {
                throw new IllegalStateException("No queued response for " + invocation.getMethod().getName());
            }
            return responses.poll();
        }
        if (returnType == Invocation.Builder.class) {
            return builder;
        }
        if (returnType == WebTarget.class) {
            return target;
        }
        return Mockito.RETURNS_DEFAULTS.answer(invocation);
    }

    /**
     * A response mock that reports the given status and returns the entity for any readEntity call.
     */
    public static Response response(final int status, final Object entity) {
        final Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        when(response.readEntity(any(Class.class))).thenReturn(entity);
        return response;
    }

    /**
     * URLs passed to client.target(...), in call order.
     */
    public List<String> targets() {
        return argsOf(client, "target").stream().map(a -> a.get(0).toString()).toList();
    }

    /**
     * Values passed to webTarget.queryParam(name, ...), in call order.
     */
    public List<String> queryParams(final String name) {
        return argsOf(target, "queryParam").stream()
                .filter(a -> name.equals(a.get(0)))
                .map(a -> a.size() > 1 ? Objects.toString(a.get(1)) : "")
                .toList();
    }

    /**
     * Values passed to builder.header(name, value), in call order.
     */
    public List<String> headers(final String name) {
        return argsOf(builder, "header").stream()
                .filter(a -> name.equals(a.get(0)))
                .map(a -> Objects.toString(a.get(1)))
                .toList();
    }

    /**
     * Entities passed to builder.post(entity), in call order.
     */
    public List<Object> postedEntities() {
        return argsOf(builder, "post").stream()
                .map(a -> ((Entity<?>) a.get(0)).getEntity())
                .map(Object.class::cast)
                .toList();
    }

    private static List<List<Object>> argsOf(final Object mock, final String method) {
        return Mockito.mockingDetails(mock).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(method))
                .map(FakeJaxRs::expandedArgs)
                .toList();
    }

    private static List<Object> expandedArgs(final org.mockito.invocation.Invocation invocation) {
        // getArguments() expands varargs, so queryParam("q", value) yields ["q", value]
        return Arrays.asList(invocation.getArguments());
    }
}
