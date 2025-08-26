package secondbrain.domain.httpclient;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TryHttpClientCalled implements HttpClientCaller {

    @Override
    public <T> T call(final ClientBuilder builder, final ClientCallback callback, final ResponseCallback<T> responseCallback, final ExceptionBuilder exceptionBuilder) {
        return Try.withResources(builder::buildClient)
                .of(client -> Try.withResources(() -> callback.call(client))
                        .of(responseCallback::handleResponse)
                        .get())
                .getOrElseThrow(exceptionBuilder::buildException);

    }
}
