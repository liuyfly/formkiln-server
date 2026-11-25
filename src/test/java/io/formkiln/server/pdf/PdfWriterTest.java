package io.formkiln.server.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PdfWriterTest {

    public static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    static int pages(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    @Test
    void replacesCharactersHelveticaCannotShow() throws IOException {
        byte[] pdf;
        try (PdfWriter writer = new PdfWriter(null, "footer")) {
            pdf = writer.title("Café ✓").field("Name", "张三 Ada", 0).toBytes();
        }
        String text = text(pdf);
        assertThat(text).contains("Café ?").contains("?? Ada");
    }

    @Test
    void wrapsLongTextAcrossPages() throws IOException {
        byte[] pdf;
        try (PdfWriter writer = new PdfWriter(null, "footer")) {
            writer.title("Long");
            writer.field("Story", "word ".repeat(3000) + "x".repeat(400), 0);
            pdf = writer.toBytes();
        }
        assertThat(pages(pdf)).isGreaterThan(1);
        assertThat(text(pdf)).contains("footer   2");
    }

    @Test
    void embedsATrueTypeFont(@TempDir Path dir) throws IOException {
        Path font = dir.resolve("font.ttf");
        try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            Files.copy(in, font, StandardCopyOption.REPLACE_EXISTING);
        }
        byte[] pdf;
        try (PdfWriter writer = new PdfWriter(font, null)) {
            pdf = writer.title("Привет Łódź").toBytes();
        }
        assertThat(text(pdf)).contains("Привет Łódź");
    }
}
