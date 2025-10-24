package secondbrain.domain.zip;

public interface Zipper {
    String compressString(String data);

    String decompressString(String compressedData);
}
