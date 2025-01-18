package secondbrain.domain.converter;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

import java.io.FileInputStream;

/**
 * A global fallback for every file type using Tika.
 */
@ApplicationScoped
public class TikaTextExtractor implements TextExtractorStrategy {
    @Override
    public String convert(final String path) {
        final BodyContentHandler handler = new BodyContentHandler();
        final Parser parser = new AutoDetectParser();
        final ParseContext context = new ParseContext();
        final Metadata metadata = new Metadata();

        Try.withResources(() -> new FileInputStream(path))
                .of(stream -> Try.run(() -> parser.parse(stream, handler, metadata, context))
                        .onFailure(Throwable::printStackTrace));

        return handler.toString();
    }

    @Override
    public boolean isSupported(String path) {
        return true;
    }

    @Override
    public int priority() {
        // Any other extractors will be more specific than this one.
        return Integer.MAX_VALUE;
    }
}
