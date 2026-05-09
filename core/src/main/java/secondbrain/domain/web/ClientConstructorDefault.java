package secondbrain.domain.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.jboss.resteasy.plugins.providers.FormUrlEncodedProvider;
import org.jboss.resteasy.plugins.providers.StringTextStar;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;

import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ClientConstructorDefault implements ClientConstructor {

    private static final int API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 30;
    private static final int API_READ_TIMEOUT_SECONDS_DEFAULT = 120;

    final List<Class> REGISTERED_CLASSES = List.of(
            ResteasyJackson2Provider.class,
            StringTextStar.class,
            FormUrlEncodedProvider.class
    );

    @Override
    public Client getClient() {
        return getClient(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, API_READ_TIMEOUT_SECONDS_DEFAULT);
    }

    @Override
    synchronized public Client getClient(final int connectionTimeout, final int readTimeout) {
        final ClientBuilder builder = ClientBuilder.newBuilder()
                .connectTimeout(connectionTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS);

        // This supports Groovy clients, where ResteasyJackson2Provider is not automatically registered
        REGISTERED_CLASSES.stream()
                .filter(c -> !builder.getConfiguration().isRegistered(c))
                .forEach(builder::register);


        return builder.build();
    }

    @Override
    public Client getClient(final int readTimeout) {
        return getClient(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, readTimeout);
    }
}
