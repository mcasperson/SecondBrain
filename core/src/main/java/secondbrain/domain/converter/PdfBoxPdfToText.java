package secondbrain.domain.converter;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

@ApplicationScoped
public class PdfBoxPdfToText implements PdfToText {
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
}
