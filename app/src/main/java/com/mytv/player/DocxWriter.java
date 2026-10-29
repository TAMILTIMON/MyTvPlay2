package com.mytv.player;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A Word document (.docx) with one picture per page (whiteboard pages / annotated slides), A4 landscape.
 * Plain Java (no Android) so it can be checked anywhere.
 */
public final class DocxWriter {
    private DocxWriter() {
    }

    /** pngs: the page pictures; w / h: their size in pixels (all pages the same shape). */
    public static void write(String path, String title, List<byte[]> pngs, int w, int h) throws IOException {
        try (FileOutputStream f = new FileOutputStream(path)) {
            write(f, title, pngs, w, h);
        }
    }

    public static void write(OutputStream os, String title, List<byte[]> pngs, int w, int h) throws IOException {
        ZipOutputStream z = new ZipOutputStream(os);
        put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "</Types>");
        put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "</Relationships>");
        put(z, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + esc(title) + "</dc:title><dc:creator>ZH Play</dc:creator></cp:coreProperties>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        // A4 landscape, 0.5" margins: usable width 15398 twips; 1 twip = 635 EMU
        long cx = 15398L * 635L, cy = cx * h / Math.max(1, w);
        long maxCy = (11906L - 1440L) * 635L;                              // keep a page on one page
        if (cy > maxCy) {
            cx = cx * maxCy / cy;
            cy = maxCy;
        }
        StringBuilder doc = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>");
        for (int i = 0; i < pngs.size(); i++) {
            int n = i + 1;
            z.putNextEntry(new ZipEntry("word/media/page" + n + ".png"));
            z.write(pngs.get(i));
            z.closeEntry();
            rels.append("<Relationship Id=\"rIdP").append(n)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/page")
                    .append(n).append(".png\"/>");
            if (i > 0) doc.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            doc.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                    .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                    .append("<wp:docPr id=\"").append(n).append("\" name=\"Page ").append(n).append("\"/>")
                    .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>")
                    .append("<pic:nvPicPr><pic:cNvPr id=\"").append(n).append("\" name=\"page").append(n).append(".png\"/><pic:cNvPicPr/></pic:nvPicPr>")
                    .append("<pic:blipFill><a:blip r:embed=\"rIdP").append(n).append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                    .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
                    .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>")
                    .append("</wp:inline></w:drawing></w:r></w:p>");
        }
        doc.append("<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/>"
                + "<w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>"
                + "</w:sectPr></w:body></w:document>");
        rels.append("</Relationships>");
        put(z, "word/document.xml", doc.toString());
        put(z, "word/_rels/document.xml.rels", rels.toString());
        z.finish();
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
