package secondbrain.domain.zip;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.persist.TimedOperation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * A Zipper implementation that uses Apache Commons Compress to perform ZStd compression and decompression.
 * The compressed data is encoded in Base64 to ensure safe string representation.
 */
@ApplicationScoped
@Identifier("ApacheCommonsZStdZipper")
public class ApacheCommonsZStdZipper implements Zipper {
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final int ZSTD_COMPRESSION_LEVEL = 22;

    @Inject
    private Logger logger;

    @Override
    public String compressString(final String data) {
        return Try.withResources(() -> new TimedOperation("text compression"))
                .of(t -> compressStringTimed(data))
                .get();
    }

    @Nullable
    private String compressStringTimed(final String data) {
        if (StringUtils.isEmpty(data)) {
            return null;
        }

        return Try.withResources(ByteArrayOutputStream::new)
                .of(bos -> Try.withResources(() -> new ZstdCompressorOutputStream(bos, ZSTD_COMPRESSION_LEVEL))
                        .of(zcos -> writeStream(zcos, bos, data))
                        .get())
                .map(ByteArrayOutputStream::toByteArray)
                .map(bytes -> Base64.getEncoder().encodeToString(bytes))
                .onFailure(ex -> logger.warning("Failed to compress data: " + ex.getMessage()))
                .get();
    }

    @Override
    public String decompressString(final String compressedData) {
        return Try.withResources(() -> new TimedOperation("text decompression"))
                .of(t -> decompressStringTimed(compressedData))
                .get();
    }

    @Nullable
    private String decompressStringTimed(final String compressedData) {
        if (StringUtils.isEmpty(compressedData)) {
            return null;
        }

        final byte[] decoded = Base64.getDecoder().decode(compressedData);

        final byte[] uncompressed = Try.withResources(() -> new ByteArrayInputStream(decoded), ByteArrayOutputStream::new)
                .of((bis, bos) -> Try.withResources(() -> new ZstdCompressorInputStream(bis))
                        .of(zcis -> copyStream(zcis, bos))
                        .mapTry(ByteArrayOutputStream::toByteArray)
                        .get())
                .onFailure(ex -> logger.fine("Failed to decompress data: " + ex.getMessage()))
                .get();

        return new String(uncompressed, DEFAULT_CHARSET);
    }

    private ByteArrayOutputStream writeStream(final ZstdCompressorOutputStream zcos, final ByteArrayOutputStream bos, final String data) throws Exception {
        zcos.write(data.getBytes(DEFAULT_CHARSET));
        return bos;
    }

    private ByteArrayOutputStream copyStream(final ZstdCompressorInputStream zcis, final ByteArrayOutputStream bos) throws Exception {
        byte[] buffer = new byte[1024];
        int n;
        while ((n = zcis.read(buffer)) > 0) {
            bos.write(buffer, 0, n);
        }
        return bos;
    }
}
