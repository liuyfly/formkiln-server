package io.formkiln.server.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * A small text-only PDF layout: a title, metadata lines, headings and label/value pairs on A4
 * pages, with word wrapping and page breaks.
 *
 * <p>Fonts: Helvetica by default (Latin-1 only; other characters are replaced with {@code ?}),
 * or a TrueType font given by path (embedded as a subset), for example Noto Sans SC for Chinese.
 */
public final class PdfWriter implements AutoCloseable {

    private static final float MARGIN = 56;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float GRAY = 0.4f;

    private final PDDocument document = new PDDocument();
    private final PDFont regular;
    private final PDFont bold;
    private final boolean customFont;
    private PDPageContentStream stream;
    private float y;
    private int pages;
    private final String footer;

    /** @param fontPath a TrueType font, or null for Helvetica */
    public PdfWriter(Path fontPath, String footer) {
        this.footer = footer;
        try {
            if (fontPath != null) {
                regular = PDType0Font.load(document, fontPath.toFile());
                bold = regular;
                customFont = true;
            } else {
                regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                customFont = false;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load the PDF font " + fontPath, e);
        }
    }

    /** Replaces characters the font cannot show (and control characters) with '?'. */
    String printable(PDFont font, String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            String c = new String(Character.toChars(cp));
            if (Character.isISOControl(cp)) {
                out.append(' ');
                return;
            }
            try {
                font.encode(c);
                out.append(c);
            } catch (IOException | IllegalArgumentException e) {
                out.append('?');
            }
        });
        return out.toString();
    }

    private float width(PDFont font, float size, String text) {
        try {
            return font.getStringWidth(text) / 1000 * size;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Splits text into lines that fit: by words, or by characters for long words and CJK text. */
    List<String> wrap(PDFont font, float size, String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : printable(font, text).split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split("(?<= )")) {
                if (width(font, size, line + word) <= maxWidth) {
                    line.append(word);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString().stripTrailing());
                    line.setLength(0);
                }
                // A word longer than the line is broken by characters.
                StringBuilder part = new StringBuilder();
                for (int cp : word.codePoints().toArray()) {
                    String c = new String(Character.toChars(cp));
                    if (width(font, size, part + c) > maxWidth && !part.isEmpty()) {
                        lines.add(part.toString());
                        part.setLength(0);
                    }
                    part.append(c);
                }
                line.append(part);
            }
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }

    private void newPage() throws IOException {
        if (stream != null) {
            finishPage();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        pages++;
        y = PDRectangle.A4.getHeight() - MARGIN;
    }

    private void finishPage() throws IOException {
        if (footer != null) {
            text(regular, 8, GRAY, MARGIN, MARGIN / 2, footer + "   " + pages);
        }
        stream.close();
    }

    private void text(PDFont font, float size, float gray, float x, float baseline, String value) throws IOException {
        stream.beginText();
        stream.setNonStrokingColor(gray, gray, gray);
        stream.setFont(font, size);
        stream.newLineAtOffset(x, baseline);
        stream.showText(value);
        stream.endText();
    }

    private void lines(PDFont font, float size, float gray, float indent, String value, float after) {
        try {
            if (stream == null) {
                newPage();
            }
            float leading = size * 1.35f;
            for (String line : wrap(font, size, value, WIDTH - indent)) {
                if (y - leading < MARGIN) {
                    newPage();
                }
                y -= leading;
                text(font, size, gray, MARGIN + indent, y, line);
            }
            y -= after;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public PdfWriter title(String value) {
        lines(bold, customFont ? 18 : 18, 0, 0, value, 6);
        return this;
    }

    public PdfWriter meta(String value) {
        lines(regular, 9, GRAY, 0, value, 0);
        return this;
    }

    public PdfWriter gap(float points) {
        y -= points;
        return this;
    }

    public PdfWriter heading(String value, float indent) {
        y -= 6;
        lines(bold, 12, 0, indent, value, 4);
        return this;
    }

    /** A label above its value. */
    public PdfWriter field(String label, String value, float indent) {
        lines(bold, 9, GRAY, indent, label, 1);
        lines(regular, 11, 0, indent, value == null || value.isBlank() ? "—" : value, 8);
        return this;
    }

    public byte[] toBytes() {
        try {
            if (stream == null) {
                newPage();
            }
            finishPage();
            stream = null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            document.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
