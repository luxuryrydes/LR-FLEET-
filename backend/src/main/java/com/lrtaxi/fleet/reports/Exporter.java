package com.lrtaxi.fleet.reports;

import com.lrtaxi.fleet.calc.Calc;
import com.lrtaxi.fleet.calc.IndianFormat;
import com.lrtaxi.fleet.common.ApiException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * XLSX and CSV writer for lists and reports.
 * XLSX: real numbers and dates with ₹ (Indian grouping) and DD-MM-YYYY formats.
 * CSV: UTF-8 with BOM, dates DD-MM-YYYY, money as plain 2-decimal numbers under a "(₹)" header so it can be re-used.
 */
public final class Exporter {

    public enum Type { TEXT, INTEGER, DECIMAL, MONEY, DATE, DATETIME }

    public record Col(String header, String key, Type type) {
    }

    private static final String INR_FORMAT =
            "[>=10000000]\"₹\"##\\,##\\,##\\,##0.00;[>=100000]\"₹\"##\\,##\\,##0.00;\"₹\"#,##0.00";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private Exporter() {
    }

    public static Col col(String h, String k) {
        return new Col(h, k, Type.TEXT);
    }

    public static Col num(String h, String k) {
        return new Col(h, k, Type.DECIMAL);
    }

    public static Col integer(String h, String k) {
        return new Col(h, k, Type.INTEGER);
    }

    public static Col money(String h, String k) {
        return new Col(h, k, Type.MONEY);
    }

    public static Col date(String h, String k) {
        return new Col(h, k, Type.DATE);
    }

    public static Col datetime(String h, String k) {
        return new Col(h, k, Type.DATETIME);
    }

    public static ResponseEntity<byte[]> respond(String baseName, String format, List<Col> cols, List<Map<String, Object>> rows) {
        return respond(baseName, format, cols, rows, List.of());
    }

    public static ResponseEntity<byte[]> respond(String baseName, String format, List<Col> cols, List<Map<String, Object>> rows, List<String> notes) {
        String f = format == null ? "xlsx" : format.toLowerCase();
        String filename = baseName + "_" + IndianFormat.date(LocalDate.now());
        byte[] body;
        MediaType type;
        if (f.equals("csv")) {
            body = csv(cols, rows);
            type = new MediaType("text", "csv", StandardCharsets.UTF_8);
            filename += ".csv";
        } else if (f.equals("xlsx")) {
            body = xlsx(baseName, cols, rows, notes);
            type = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            filename += ".xlsx";
        } else {
            throw ApiException.badRequest("INVALID_FORMAT", "Format must be xlsx or csv");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(type)
                .body(body);
    }

    public static byte[] csv(List<Col> cols, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Col c = cols.get(i);
            sb.append(escape(c.type() == Type.MONEY ? c.header() + " (₹)" : c.header()));
        }
        sb.append("\r\n");
        for (Map<String, Object> r : rows) {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(escape(csvValue(cols.get(i), r.get(cols.get(i).key()))));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvValue(Col c, Object v) {
        if (v == null) {
            return "";
        }
        return switch (c.type()) {
            case MONEY -> Calc.money(toDecimal(v)).toPlainString();
            case DATE -> v instanceof LocalDate d ? IndianFormat.date(d) : String.valueOf(v);
            case DATETIME -> v instanceof Instant t ? IndianFormat.date(LocalDate.ofInstant(t, IST)) + " "
                    + LocalDateTime.ofInstant(t, IST).toLocalTime().withNano(0) : String.valueOf(v);
            case DECIMAL, INTEGER -> v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v);
            default -> String.valueOf(v);
        };
    }

    /** Quotes when needed and neutralises spreadsheet formula injection (=, +, -, @ at the start of text). */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        String out = s;
        if (!out.isEmpty() && "=+-@".indexOf(out.charAt(0)) >= 0 && !out.matches("-?\\d+(\\.\\d+)?")) {
            out = "'" + out;
        }
        if (out.contains(",") || out.contains("\"") || out.contains("\n") || out.contains("\r")) {
            out = "\"" + out.replace("\"", "\"\"") + "\"";
        }
        return out;
    }

    public static byte[] xlsx(String title, List<Col> cols, List<Map<String, Object>> rows, List<String> notes) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat df = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(df.getFormat(INR_FORMAT));
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(df.getFormat("dd-mm-yyyy"));
            CellStyle dateTime = wb.createCellStyle();
            dateTime.setDataFormat(df.getFormat("dd-mm-yyyy hh:mm"));
            CellStyle decimal = wb.createCellStyle();
            decimal.setDataFormat(df.getFormat("#,##0.00#"));

            String sheetName = title.length() > 31 ? title.substring(0, 31) : title;
            SXSSFSheet sheet = wb.createSheet(sheetName.replaceAll("[\\\\/?*\\[\\]:]", "_"));
            sheet.trackAllColumnsForAutoSizing();
            Row h = sheet.createRow(0);
            for (int i = 0; i < cols.size(); i++) {
                Cell cell = h.createCell(i);
                cell.setCellValue(cols.get(i).header());
                cell.setCellStyle(header);
            }
            sheet.createFreezePane(0, 1);
            int r = 1;
            for (Map<String, Object> row : rows) {
                Row xr = sheet.createRow(r++);
                for (int i = 0; i < cols.size(); i++) {
                    Object v = row.get(cols.get(i).key());
                    if (v == null) {
                        continue;
                    }
                    Cell cell = xr.createCell(i);
                    switch (cols.get(i).type()) {
                        case MONEY -> {
                            cell.setCellValue(Calc.money(toDecimal(v)).doubleValue());   // display only; values already rounded
                            cell.setCellStyle(money);
                        }
                        case DECIMAL -> {
                            cell.setCellValue(toDecimal(v).doubleValue());
                            cell.setCellStyle(decimal);
                        }
                        case INTEGER -> cell.setCellValue(toDecimal(v).doubleValue());
                        case DATE -> {
                            if (v instanceof LocalDate d) {
                                cell.setCellValue(d);
                                cell.setCellStyle(date);
                            } else {
                                cell.setCellValue(String.valueOf(v));
                            }
                        }
                        case DATETIME -> {
                            if (v instanceof Instant t) {
                                cell.setCellValue(LocalDateTime.ofInstant(t, IST));
                                cell.setCellStyle(dateTime);
                            } else {
                                cell.setCellValue(String.valueOf(v));
                            }
                        }
                        default -> cell.setCellValue(String.valueOf(v));
                    }
                }
            }
            for (int i = 0; i < cols.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            if (notes != null && !notes.isEmpty()) {
                Sheet n = wb.createSheet("Notes");
                int i = 0;
                for (String note : notes) {
                    n.createRow(i++).createCell(0).setCellValue(note);
                }
                n.setColumnWidth(0, 160 * 256);
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build Excel file", e);
        }
    }

    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(String.valueOf(v));
    }
}
