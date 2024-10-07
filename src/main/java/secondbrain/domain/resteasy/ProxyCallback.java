package secondbrain.domain.resteasy;

@FunctionalInterface
public interface ProxyCallback<T, U> {
    U callProxy(T proxy);
}
