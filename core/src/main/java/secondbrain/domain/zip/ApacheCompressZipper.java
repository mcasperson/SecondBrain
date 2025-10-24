package secondbrain.domain.zip;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class ApacheCompressZipper implements Zipper {
    @Override
    public byte[] compressString(final String data) {
        if (StringUtils.isEmpty(data)) {
            return new byte[0];
        }

        return Try.withResources(ByteArrayOutputStream::new)
                .of(bos -> Try.withResources(() -> new GzipCompressorOutputStream(bos))
                        .of(gcos -> writeStream(gcos, bos, data))
                        .get())
                .map(ByteArrayOutputStream::toByteArray)
                .get();
    }

    @Override
    public String decompressBytes(byte[] compressedData) {
        if (compressedData == null || compressedData.length == 0) {
            return "";
        }

        final byte[] uncompressed = Try.withResources(() -> new ByteArrayInputStream(compressedData), ByteArrayOutputStream::new)
                .of((bis, bos) -> Try.withResources(() -> new GzipCompressorInputStream(bis))
                        .of(gcis -> copyStream(gcis, bos))
                        .mapTry(ByteArrayOutputStream::toByteArray)
                        .get())
                .get();

        return new String(uncompressed);
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
