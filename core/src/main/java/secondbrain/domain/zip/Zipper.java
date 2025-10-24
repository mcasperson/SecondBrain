package secondbrain.domain.zip;

public interface Zipper {
    byte[] compressString(String data);

    String decompressBytes(byte[] compressedData);
}
