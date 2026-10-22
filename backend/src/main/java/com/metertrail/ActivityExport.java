package com.metertrail;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class ActivityExport {
  static String[] headers(boolean zh) {
    return zh
        ? new String[] {"对象", "类型", "模型", "成本 USD", "状态", "时间 UTC"}
        : new String[] {"Object", "Type", "Model", "Cost USD", "Status", "Time UTC"};
  }

  static String[] row(Map<String, Object> r, boolean zh) {
    var d = Support.parse(r.get("data").toString());
    String cost =
        !"observation".equals(r.get("type"))
            ? "—"
            : r.get("cost") == null ? (zh ? "未知" : "Unknown") : r.get("cost").toString();
    return new String[] {
      d.path("name").asText(r.get("object_id").toString()),
      r.get("type").toString(),
      d.path("model").asText("—"),
      cost,
      d.path("status").asText(d.path("valueType").asText("—")),
      java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
          .withZone(java.time.ZoneOffset.UTC)
          .format(((java.sql.Timestamp) r.get("started_at")).toInstant())
    };
  }

  static String xml(String s) {
    var out = new StringBuilder();
    s.codePoints()
        .filter(
            c ->
                c == 9
                    || c == 10
                    || c == 13
                    || c >= 32 && c <= 0x10ffff && c != 0xfffe && c != 0xffff)
        .forEach(out::appendCodePoint);
    return out.toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  static final class Excel implements AutoCloseable {
    final ZipOutputStream zip;
    int sheet = 0, row = 0;
    final String[] headers;

    Excel(OutputStream out, boolean zh) throws IOException {
      this(out, headers(zh));
    }

    Excel(OutputStream out, String[] labels) throws IOException {
      zip = new ZipOutputStream(out);
      headers = labels;
      startSheet();
    }

    void write(String s) throws IOException {
      zip.write(s.getBytes(StandardCharsets.UTF_8));
    }

    void entry(String name, String content) throws IOException {
      zip.putNextEntry(new ZipEntry(name));
      write(content);
      zip.closeEntry();
    }

    void startSheet() throws IOException {
      sheet++;
      row = 0;
      zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + sheet + ".xml"));
      write(
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet"
              + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView"
              + " workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\""
              + " state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"1\""
              + " width=\"28\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"48\""
              + " customWidth=\"1\"/><col min=\"3\" max=\"6\" width=\"24\""
              + " customWidth=\"1\"/></cols><sheetData>");
      add(headers);
    }

    void add(String[] values) throws IOException {
      // Split at Excel's row limit; never silently truncate the export.
      if (row == 1048576) {
        endSheet();
        startSheet();
      }
      write("<row r=\"" + (++row) + "\">");
      for (int i = 0; i < values.length; i++)
        write(
            "<c r=\""
                + (char) ('A' + i)
                + row
                + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + xml(values[i])
                + "</t></is></c>");
      write("</row>");
    }

    void endSheet() throws IOException {
      write("</sheetData></worksheet>");
      zip.closeEntry();
    }

    public void close() throws IOException {
      endSheet();
      var types =
          new StringBuilder(
              "<Types"
                  + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
                  + " Extension=\"rels\""
                  + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
                  + " Extension=\"xml\" ContentType=\"application/xml\"/><Override"
                  + " PartName=\"/xl/workbook.xml\""
                  + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
      var sheets = new StringBuilder();
      var rels =
          new StringBuilder(
              "<Relationships"
                  + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
      for (int i = 1; i <= sheet; i++) {
        types
            .append("<Override PartName=\"/xl/worksheets/sheet")
            .append(i)
            .append(
                ".xml\""
                    + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        sheets
            .append("<sheet name=\"Activity ")
            .append(i)
            .append("\" sheetId=\"")
            .append(i)
            .append("\" r:id=\"rId")
            .append(i)
            .append("\"/>");
        rels.append("<Relationship Id=\"rId")
            .append(i)
            .append(
                "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
                    + " Target=\"worksheets/sheet")
            .append(i)
            .append(".xml\"/>");
      }
      entry("[Content_Types].xml", types + "</Types>");
      entry(
          "_rels/.rels",
          "<Relationships"
              + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
              + " Id=\"rId1\""
              + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
              + " Target=\"xl/workbook.xml\"/></Relationships>");
      entry(
          "xl/workbook.xml",
          "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
              + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
              + sheets
              + "</sheets></workbook>");
      entry("xl/_rels/workbook.xml.rels", rels + "</Relationships>");
      zip.finish();
    }
  }

  static final class Pdf implements AutoCloseable {
    final PDDocument doc = new PDDocument();
    final PDType0Font font;
    final String[] headers;
    final float[] widths;
    PDPageContentStream stream;
    float y;

    Pdf(boolean zh) throws IOException {
      this(headers(zh), new float[] {210, 90, 130, 100, 110, 130});
    }

    Pdf(String[] labels, float[] columnWidths) throws IOException {
      headers = labels;
      widths = columnWidths;
      try (var in = ActivityExport.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
        font = PDType0Font.load(doc, Objects.requireNonNull(in));
      }
      newPage();
    }

    void newPage() throws IOException {
      if (stream != null) stream.close();
      var page = new PDPage(new PDRectangle(842, 595));
      doc.addPage(page);
      stream = new PDPageContentStream(doc, page);
      y = 553;
      text("MeterTrail  /  Report  /  UTC  /  " + doc.getNumberOfPages(), 32, y, 12);
      y -= 28;
      float x = 32;
      for (int i = 0; i < headers.length; i++) {
        text(headers[i], x, y, 9);
        x += widths[i];
      }
      y -= 20;
    }

    String printable(String s) {
      var out = new StringBuilder();
      s.codePoints()
          .forEach(
              c -> {
                String v = new String(Character.toChars(c));
                try {
                  font.encode(v);
                  out.append(v);
                } catch (Exception ex) {
                  out.append("[U+").append(Integer.toHexString(c)).append("]");
                }
              });
      return out.toString();
    }

    List<String> wrap(String s, float width) throws IOException {
      List<String> lines = new ArrayList<>();
      StringBuilder line = new StringBuilder();
      for (int c : printable(s).codePoints().toArray()) {
        if (c == 10 || c == 13) {
          lines.add(line.toString());
          line.setLength(0);
          continue;
        }
        String next = new String(Character.toChars(c));
        if (font.getStringWidth(line + next) / 1000 * 9 > width - 10 && !line.isEmpty()) {
          lines.add(line.toString());
          line.setLength(0);
        }
        line.append(next);
      }
      lines.add(line.toString());
      return lines;
    }

    void text(String s, float x, float y, int size) throws IOException {
      stream.beginText();
      stream.setFont(font, size);
      stream.newLineAtOffset(x, y);
      stream.showText(s);
      stream.endText();
    }

    void add(String[] values) throws IOException {
      List<List<String>> cells = new ArrayList<>();
      int lines = 1;
      for (int i = 0; i < headers.length; i++) {
        var cell = wrap(values[i], widths[i]);
        cells.add(cell);
        lines = Math.max(lines, cell.size());
      }
      for (int line = 0; line < lines; line++) {
        if (y < 35) newPage();
        float x = 32;
        for (int i = 0; i < headers.length; i++) {
          if (line < cells.get(i).size()) text(cells.get(i).get(line), x, y, 9);
          x += widths[i];
        }
        y -= 14;
      }
      y -= 8;
    }

    void save(OutputStream out) throws IOException {
      stream.close();
      stream = null;
      doc.save(out);
    }

    public void close() throws IOException {
      if (stream != null) stream.close();
      doc.close();
    }
  }
}
