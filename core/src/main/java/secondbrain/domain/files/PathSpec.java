package secondbrain.domain.files;

public interface PathSpec {
    boolean matches(final String antPattern, final String filePath);
}
