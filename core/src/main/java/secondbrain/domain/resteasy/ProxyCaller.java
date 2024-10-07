package secondbrain.domain.resteasy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.ClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;

@ApplicationScoped
public class ProxyCaller {
     public <T, U> U callProxy(@NotNull final String uri, @NotNull Class<T> proxyInterface, @NotNull ProxyCallback<T, U> callback) {
        ResteasyClient client = null;
        try  {
            client = (ResteasyClient) ClientBuilder.newClient();
            final ResteasyWebTarget target = client.target(uri);
            return callback.callProxy(target.proxy(proxyInterface));
        } catch (final ClientErrorException ex) {
            System.out.println(ex.getMessage());
            throw ex;
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }
}
