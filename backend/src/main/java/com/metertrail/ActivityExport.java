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
              + " showGridLines=\"0\" workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\""
              + " state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr"
              + " defaultRowHeight=\"24\"/><cols>");
      for (int i = 0; i < headers.length; i++) {
        String label = headers[i].toLowerCase(Locale.ROOT);
        int width =
            label.contains("id") || label.contains("目标") || label.contains("target")
                ? 42
                : label.contains("时间") || label.contains("utc") ? 28 : i == 0 ? 30 : 24;
        write(
            "<col min=\""
                + (i + 1)
                + "\" max=\""
                + (i + 1)
                + "\" width=\""
                + width
                + "\" customWidth=\"1\"/>");
      }
      write("</cols><sheetData>");
      add(headers);
    }

    void add(String[] values) throws IOException {
      // Split at Excel's row limit; never silently truncate the export.
      if (row == 1048576) {
        endSheet();
        startSheet();
      }
      write("<row r=\"" + (++row) + "\" ht=\"" + (row == 1 ? 32 : 28) + "\" customHeight=\"1\">");
      for (int i = 0; i < values.length; i++)
        write(
            "<c r=\""
                + (char) ('A' + i)
                + row
                + "\" s=\""
                + (row == 1 ? 1 : row % 2 == 0 ? 2 : 3)
                + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + xml(values[i])
                + "</t></is></c>");
      write("</row>");
    }

    void endSheet() throws IOException {
      write(
          "</sheetData><autoFilter ref=\"A1:"
              + (char) ('A' + headers.length - 1)
              + row
              + "\"/><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\"/><pageSetup"
              + " orientation=\"landscape\" paperSize=\"9\"/></worksheet>");
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
      types.append(
          "<Override PartName=\"/xl/styles.xml\""
              + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
      rels.append(
          "<Relationship Id=\"styles\""
              + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\""
              + " Target=\"styles.xml\"/>");
      entry(
          "xl/styles.xml",
          """
          <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <fonts count="2"><font><sz val="11"/><color rgb="FF324C6F"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
          <fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF2445B7"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF2F5FC"/><bgColor indexed="64"/></patternFill></fill></fills>
          <borders count="2"><border/><border><bottom style="hair"><color rgb="FFE1E7F0"/></bottom></border></borders>
          <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
          <cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" indent="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyAlignment="1"><alignment vertical="center" indent="1"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyAlignment="1"><alignment vertical="center" indent="1"/></xf></cellXfs>
          <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
          </styleSheet>
          """);
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
    final float tableWidth;
    PDPageContentStream stream;
    float y;
    int rowNumber;

    Pdf(boolean zh) throws IOException {
      this(headers(zh), new float[] {210, 90, 130, 100, 110, 130});
    }

    Pdf(String[] labels, float[] columnWidths) throws IOException {
      headers = labels;
      widths = columnWidths.clone();
      float total = 0;
      for (float width : widths) total += width;
      tableWidth = Math.max(770, total);
      try (var in = ActivityExport.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
        font = PDType0Font.load(doc, Objects.requireNonNull(in));
      }
      newPage();
    }

    void newPage() throws IOException {
      if (stream != null) stream.close();
      var page = new PDPage(new PDRectangle(tableWidth + 72, 595));
      doc.addPage(page);
      stream = new PDPageContentStream(doc, page);
      stream.setNonStrokingColor(new java.awt.Color(36, 69, 183));
      stream.addRect(32, 555, 32, 4);
      stream.fill();
      stream.setNonStrokingColor(new java.awt.Color(23, 42, 67));
      text("MeterTrail / Data report", 32, 530, 18);
      stream.setNonStrokingColor(new java.awt.Color(110, 126, 149));
      text(
          "Exported "
              + java.time.LocalDate.now(java.time.ZoneOffset.UTC)
              + "  |  All matching records  |  UTC",
          32,
          510,
          9);
      text("MeterTrail  /  " + doc.getNumberOfPages(), 32, 20, 8);
      stream.setNonStrokingColor(new java.awt.Color(36, 69, 183));
      stream.addRect(32, 452, tableWidth, 42);
      stream.fill();
      stream.setNonStrokingColor(new java.awt.Color(255, 255, 255));
      float x = 38;
      for (int i = 0; i < headers.length; i++) {
        var lines = wrap(headers[i], widths[i]);
        for (int line = 0; line < lines.size(); line++)
          text(lines.get(line), x, 479 - line * 12, 9);
        x += widths[i];
      }
      y = 435;
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
      if (y - lines * 14 - 8 < 40 && lines * 14 < 400) newPage();
      rowNumber++;
      for (int line = 0; line < lines; line++) {
        if (y < 35) newPage();
        if (rowNumber % 2 == 0) {
          stream.setNonStrokingColor(new java.awt.Color(242, 245, 252));
          stream.addRect(32, y - 5, tableWidth, 14);
          stream.fill();
        }
        stream.setNonStrokingColor(new java.awt.Color(50, 76, 111));
        float x = 38;
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
