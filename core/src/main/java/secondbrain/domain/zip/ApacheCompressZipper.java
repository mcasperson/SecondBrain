package secondbrain.domain.zip;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.persist.TimedOperation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A Zipper implementation that uses Apache Commons Compress to perform GZIP compression and decompression.
 * The compressed data is encoded in Base64 to ensure safe string representation.
 */
@ApplicationScoped
public class ApacheCompressZipper implements Zipper {
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    @Override
    public String compressString(final String data) {
        if (StringUtils.isEmpty(data)) {
            return null;
        }

        final GzipParameters parameters = new GzipParameters();
        parameters.setCompressionLevel(9); // Maximum compression

        return Try.withResources(ByteArrayOutputStream::new)
                .of(bos -> Try.withResources(() -> new GzipCompressorOutputStream(bos, parameters))
                        .of(gcos -> writeStream(gcos, bos, data))
                        .get())
                .map(ByteArrayOutputStream::toByteArray)
                .map(inputBytes -> Base64.getEncoder().encodeToString(inputBytes))
                .get();
    }

    @Override
    public String decompressString(final String compressedData) {
        return Try.withResources(() -> new TimedOperation("text decompression"))
                .of(t -> decompressStringTimed(compressedData))
                .get();
    }

    private String decompressStringTimed(final String compressedData) {
        if (StringUtils.isEmpty(compressedData)) {
            return null;
        }

        final byte[] decoded = Base64.getDecoder().decode(compressedData);

        final byte[] uncompressed = Try.withResources(() -> new ByteArrayInputStream(decoded), ByteArrayOutputStream::new)
                .of((bis, bos) -> Try.withResources(() -> new GzipCompressorInputStream(bis))
                        .of(gcis -> copyStream(gcis, bos))
                        .mapTry(ByteArrayOutputStream::toByteArray)
                        .get())
                .get();

        return new String(uncompressed, DEFAULT_CHARSET);
    }

    private ByteArrayOutputStream writeStream(final GzipCompressorOutputStream gcos, final ByteArrayOutputStream bos, final String data) throws Exception {
        gcos.write(data.getBytes(DEFAULT_CHARSET));
        return bos;
    }

    private ByteArrayOutputStream copyStream(final GzipCompressorInputStream gcis, final ByteArrayOutputStream bos) throws Exception {
        byte[] buffer = new byte[1024];
        int n;
        while ((n = gcis.read(buffer)) > 0) {
            bos.write(buffer, 0, n);
        }
        return bos;
    }
}
