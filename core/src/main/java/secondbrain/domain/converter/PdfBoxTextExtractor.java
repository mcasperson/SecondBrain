package secondbrain.domain.converter;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

@ApplicationScoped
public class PdfBoxTextExtractor implements TextExtractorStrategy {
    @Override
    public String convert(final String pdfPath) {
        return Try.withResources(() -> Loader.loadPDF(new File(pdfPath)))
                .of(document -> {
                    final PDFTextStripper pdfStripper = new PDFTextStripper();
                    return pdfStripper.getText(document);
                })
                .onFailure(Throwable::printStackTrace)
                .getOrNull();
    }

    @Override
    public boolean isSupported(final String path) {
        return StringUtils.endsWith(path.toLowerCase(), ".pdf");
    }
}
