package secondbrain.domain.httpclient;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.tryext.TryExtensions;

@ApplicationScoped
public class TryHttpClientCalled implements HttpClientCaller {

    @Override
    public <T> T call(final ClientBuilder builder, final ClientCallback callback, final ResponseCallback<T> responseCallback, final ExceptionBuilder exceptionBuilder) {
        return TryExtensions.withResources(
                        builder::buildClient,
                        callback::call,
                        responseCallback::handleResponse)
                .getOrElseThrow(exceptionBuilder::buildException);
    }
}
