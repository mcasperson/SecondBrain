package secondbrain.domain.files;

public interface FileSanitizer {
    String sanitizeFilePath(String fileName);

    String sanitizeFileName(String fileName);
}
