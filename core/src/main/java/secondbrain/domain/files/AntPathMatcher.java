package secondbrain.domain.files;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.tools.ant.types.selectors.SelectorUtils;

@ApplicationScoped
public class AntPathMatcher implements PathSpec {
    @Override
    public boolean matches(final String antPattern, final String filePath) {
        // Ant patterns use '/' as separator, so normalize
        final String normalizedPath = filePath.replace('\\', '/');
        return SelectorUtils.match(antPattern, normalizedPath);
    }
}