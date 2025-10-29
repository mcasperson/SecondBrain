package secondbrain.domain.files;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DefaultFileSanitizer implements FileSanitizer {
    @Override
    public String sanitizeFileName(final String fileName) {
        return fileName.replaceAll("[:*?\"<>|]", "_");
    }
}
