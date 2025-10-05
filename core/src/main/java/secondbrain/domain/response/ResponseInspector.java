package secondbrain.domain.response;

/**
 * This interface defines a service that can test a response to see if it matches certain criteria.
 */
public interface ResponseInspector {
    boolean isMatch(String response);
}
