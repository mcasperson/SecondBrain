package secondbrain.domain.zip;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A Zipper implementation that uses Apache Commons Compress to perform GZIP compression and decompression.
 * The compressed data is encoded in Base64 to ensure safe string representation.
 */
@ApplicationScoped
public class ApacheCompressZipper implements Zipper {
    @Override
    public String compressString(final String data) {
        if (StringUtils.isEmpty(data)) {
            return null;
        }

        return Try.withResources(ByteArrayOutputStream::new)
                .of(bos -> Try.withResources(() -> new GzipCompressorOutputStream(bos))
                        .of(gcos -> writeStream(gcos, bos, data))
                        .get())
                .map(ByteArrayOutputStream::toByteArray)
                .map(inputBytes -> Base64.getEncoder().encodeToString(inputBytes))
                .get();
    }

    @Override
    public String decompressString(final String compressedData) {
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

        return new String(uncompressed, StandardCharsets.UTF_8);
    }

    private ByteArrayOutputStream writeStream(final GzipCompressorOutputStream gcos, final ByteArrayOutputStream bos, final String data) throws Exception {
        gcos.write(data.getBytes(StandardCharsets.UTF_8));
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
