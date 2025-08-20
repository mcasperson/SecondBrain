package secondbrain.domain.httpclient;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TryHttpClientCalled implements HttpClientCaller {

    @Override
    public <T> T call(final ClientBuilder builder, final ClientCallback callback, final ResponseCallback responseCallback, final ExceptionBuilder exceptionBuilder) {
        return Try.withResources(builder::buildClient)
                .of(client -> Try.withResources(() -> callback.call(client))
                        .of(responseCallback::<T>handleResponse)
                        .get())
                .getOrElseThrow(exceptionBuilder::buildException);

    }
}
