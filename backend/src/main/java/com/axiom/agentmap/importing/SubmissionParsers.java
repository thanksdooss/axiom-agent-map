package com.axiom.agentmap.importing;

import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.importing.FieldMap.Field;
import com.axiom.agentmap.importing.SubmittedAgent.Raw;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 5가지 형식(json·yaml·csv·xlsx·docx)의 제출 파일 파서. 필드 이름은 FieldMap으로 표준 필드에 맞추고,
 * 값마다 근거 위치(JSON 포인터, YAML 행, CSV 행·열, 엑셀 셀, Word 문단·문장)를 붙입니다.
 */
public final class SubmissionParsers {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Pattern HEADING = Pattern.compile("^\\s*(\\d+)[.)]\\s*(.+?Agent)\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private SubmissionParsers() {}

    public static String format(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        return switch (ext) {
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "csv" -> "csv";
            case "xlsx" -> "xlsx";
            case "docx" -> "docx";
            default -> throw new IllegalArgumentException("지원하지 않는 파일 형식입니다(json·yaml·csv·xlsx·docx): " + fileName);
        };
    }

    public static ParsedFile parse(String fileName, byte[] bytes) {
        try {
            return switch (format(fileName)) {
                case "json" -> json(fileName, bytes);
                case "yaml" -> yaml(fileName, bytes);
                case "csv" -> csv(fileName, bytes);
                case "xlsx" -> xlsx(fileName, bytes);
                default -> docx(fileName, bytes);
            };
        } catch (IOException e) {
            throw new IllegalArgumentException("파일을 읽을 수 없습니다: " + fileName + " (" + e.getMessage() + ")", e);
        }
    }

    // ------------------------------------------------------------------ 공통

    static void assign(SubmittedAgent a, Field f, String value, Evidence ev) {
        Raw r = new Raw(value, ev);
        switch (f) {
            case NAME -> a.name = r;
            case DEPT -> a.dept = r;
            case OWNER -> a.owner = r;
            case TYPE -> a.type = r;
            case DESC -> a.desc = r;
            case DATE -> a.date = r;
            case USAGE -> a.usage = r;
            case STATUS -> a.status = r;
            case NOTE -> a.note = r;
            case PLATFORMS -> { if (value != null && !value.isBlank()) a.platforms.add(r); }
            case DATA -> { if (value != null && !value.isBlank()) a.data.add(r); }
            case CALLS -> { if (value != null && !value.isBlank()) a.calls.add(r); }
        }
    }

    private static String scalar(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        return v.asString();
    }

    private static String bom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    // ------------------------------------------------------------------ JSON

    static ParsedFile json(String name, byte[] bytes) {
        JsonNode root = JSON.readTree(bom(new String(bytes, StandardCharsets.UTF_8)));
        JsonNode arr = root.isArray() ? root : root.path("agents");
        Raw submitter = null;
        if (root.isObject()) {
            for (Map.Entry<String, JsonNode> e : root.properties()) {
                if (FieldMap.isSubmitterKey(e.getKey())) {
                    submitter = new Raw(scalar(e.getValue()), Evidence.of(name, "/" + e.getKey(), scalar(e.getValue())));
                }
            }
        }
        String base = root.isArray() ? "" : "/agents";
        List<SubmittedAgent> out = new ArrayList<>();
        int n = 0;
        for (JsonNode rec : arr) {
            SubmittedAgent a = start(name, "json", n, submitter);
            for (Map.Entry<String, JsonNode> e : rec.properties()) {
                Field f = FieldMap.of(e.getKey());
                if (f == null) continue;
                String loc = base + "/" + n + "/" + e.getKey();
                JsonNode v = e.getValue();
                if (FieldMap.isList(f)) {
                    if (v.isArray()) {
                        int j = 0;
                        for (JsonNode item : v) {
                            assign(a, f, scalar(item), Evidence.of(name, loc + "/" + j, scalar(item)));
                            j++;
                        }
                    } else if (scalar(v) != null) {
                        splitInto(a, f, scalar(v), "[;\\n]", Evidence.of(name, loc, scalar(v)));
                    }
                } else {
                    assign(a, f, scalar(v), Evidence.of(name, loc, scalar(v)));
                }
            }
            out.add(a);
            n++;
        }
        return new ParsedFile(name, "json", out, List.of());
    }

    private static SubmittedAgent start(String file, String format, int index, Raw submitter) {
        SubmittedAgent a = new SubmittedAgent();
        a.file = file;
        a.format = format;
        a.index = index;
        a.submitter = submitter;
        return a;
    }

    /** 한 칸에 여러 값이 들어 있는 경우(CSV ';', 엑셀 줄바꿈). 근거 위치는 칸 위치, 원문은 각 값 */
    private static void splitInto(SubmittedAgent a, Field f, String cell, String sep, Evidence cellEv) {
        for (String part : cell.split(sep)) {
            String v = part.strip();
            if (v.isEmpty()) continue;
            assign(a, f, v, Evidence.of(cellEv.file(), cellEv.locator(), v));
        }
    }

    // ------------------------------------------------------------------ YAML

    private static final Pattern Y_FIRST = Pattern.compile("^- ([^:]+):(.*)$");
    private static final Pattern Y_KEY = Pattern.compile("^  ([^\\s-][^:]*):(.*)$");
    private static final Pattern Y_ITEM = Pattern.compile("^  - (.*)$");
    private static final Pattern Y_TOP = Pattern.compile("^([^\\s-][^:]*):(.*)$");

    static ParsedFile yaml(String name, byte[] bytes) throws IOException {
        String text = bom(new String(bytes, StandardCharsets.UTF_8));
        JsonNode root = YAML.readTree(text);
        // 근거 위치용 행 번호: (n,key) → 행, (n,key,j) → 행
        Map<String, Integer> lines = new HashMap<>();
        String[] ls = text.split("\n", -1);
        int n = -1;
        String key = null;
        int j = 0;
        Raw submitter = null;
        for (int i = 0; i < ls.length; i++) {
            String line = ls[i].replace("\r", "");
            Matcher m;
            if ((m = Y_FIRST.matcher(line)).matches()) {
                n++;
                key = m.group(1).strip();
                j = 0;
                lines.put(n + "|" + key, i + 1);
            } else if ((m = Y_ITEM.matcher(line)).matches() && n >= 0 && key != null) {
                lines.put(n + "|" + key + "|" + j, i + 1);
                j++;
            } else if ((m = Y_KEY.matcher(line)).matches() && n >= 0) {
                key = m.group(1).strip();
                j = 0;
                lines.put(n + "|" + key, i + 1);
            } else if ((m = Y_TOP.matcher(line)).matches() && FieldMap.isSubmitterKey(m.group(1).strip())) {
                String k = m.group(1).strip();
                submitter = new Raw(m.group(2).strip(), Evidence.of(name, (i + 1) + "행 (" + k + ")", line.strip()));
            }
        }
        JsonNode arr = root.isArray() ? root : root.path("agents");
        List<SubmittedAgent> out = new ArrayList<>();
        int idx = 0;
        for (JsonNode rec : arr) {
            SubmittedAgent a = start(name, "yaml", idx, submitter);
            for (Map.Entry<String, JsonNode> e : rec.properties()) {
                Field f = FieldMap.of(e.getKey());
                if (f == null) continue;
                String k = e.getKey();
                Integer ln = lines.get(idx + "|" + k);
                String path = "agents[" + idx + "]." + k;
                JsonNode v = e.getValue();
                if (FieldMap.isList(f)) {
                    if (v.isArray()) {
                        int jj = 0;
                        for (JsonNode item : v) {
                            Integer il = lines.get(idx + "|" + k + "|" + jj);
                            assign(a, f, scalar(item), Evidence.of(name, loc(il, path + "[" + jj + "]"), scalar(item)));
                            jj++;
                        }
                    } else if (scalar(v) != null) {
                        splitInto(a, f, scalar(v), "[;\\n]", Evidence.of(name, loc(ln, path), scalar(v)));
                    }
                } else {
                    assign(a, f, scalar(v), Evidence.of(name, loc(ln, path), scalar(v)));
                }
            }
            out.add(a);
            idx++;
        }
        return new ParsedFile(name, "yaml", out, List.of());
    }

    private static String loc(Integer line, String path) {
        return line == null ? path : line + "행 (" + path + ")";
    }

    // ------------------------------------------------------------------ CSV

    static ParsedFile csv(String name, byte[] bytes) throws IOException {
        String text = bom(new String(bytes, StandardCharsets.UTF_8));
        List<SubmittedAgent> out = new ArrayList<>();
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser p = CSVParser.parse(new StringReader(text), fmt)) {
            List<String> headers = p.getHeaderNames();
            int n = 0;
            for (CSVRecord rec : p) {
                int line = (int) rec.getRecordNumber() + 1;
                SubmittedAgent a = start(name, "csv", n, null);
                for (String h : headers) {
                    Field f = FieldMap.of(h);
                    if (f == null || !rec.isMapped(h) || !rec.isSet(h)) continue;
                    String v = rec.get(h);
                    String locator = line + "행 " + h + "열";
                    if (FieldMap.isList(f)) splitInto(a, f, v, ";", Evidence.of(name, locator, v));
                    else assign(a, f, v, Evidence.of(name, locator, v));
                }
                out.add(a);
                n++;
            }
        }
        return new ParsedFile(name, "csv", out, List.of());
    }

    // ------------------------------------------------------------------ XLSX

    static ParsedFile xlsx(String name, byte[] bytes) throws IOException {
        List<SubmittedAgent> out = new ArrayList<>();
        DataFormatter df = new DataFormatter();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            String sheetName = sheet.getSheetName();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            Map<Integer, Field> cols = new HashMap<>();
            for (Cell c : header) {
                Field f = FieldMap.of(df.formatCellValue(c));
                if (f != null) cols.put(c.getColumnIndex(), f);
            }
            int n = 0;
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                SubmittedAgent a = start(name, "xlsx", n, null);
                boolean any = false;
                for (Map.Entry<Integer, Field> col : cols.entrySet()) {
                    Cell c = row.getCell(col.getKey());
                    String ref = sheetName + "!" + new CellReference(r, col.getKey()).formatAsString(false);
                    String v = cellText(c, df);
                    if (v != null && !v.isEmpty()) any = true;
                    Field f = col.getValue();
                    if (FieldMap.isList(f)) splitInto(a, f, v == null ? "" : v, "\\r?\\n", Evidence.of(name, ref, v));
                    else assign(a, f, v, Evidence.of(name, ref, v));
                }
                if (!any) continue;
                out.add(a);
                n++;
            }
        }
        return new ParsedFile(name, "xlsx", out, List.of());
    }

    private static String cellText(Cell c, DataFormatter df) {
        if (c == null) return "";
        if (c.getCellType() == CellType.NUMERIC) {
            double d = c.getNumericCellValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return df.formatCellValue(c);
    }

    // ------------------------------------------------------------------ DOCX

    static ParsedFile docx(String name, byte[] bytes) throws IOException {
        List<String> paras = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) paras.add(p.getText());
        }
        String submitter = null;
        String submitterLoc = null;
        for (int i = 0; i < paras.size(); i++) {
            String t = paras.get(i);
            if (submitter == null && (t.contains("작성:") || t.contains("제출:") || t.contains("제출부서"))) {
                submitter = t.strip();
                submitterLoc = "문단 " + (i + 1);
            }
        }
        List<ParsedFile.NarrativeSection> sections = new ArrayList<>();
        for (int i = 0; i < paras.size(); i++) {
            Matcher m = HEADING.matcher(paras.get(i));
            if (!m.matches()) continue;
            int body = i + 1;
            while (body < paras.size() && paras.get(body).isBlank()) body++;
            if (body >= paras.size() || HEADING.matcher(paras.get(body)).matches()) continue;
            List<String> sentences = new ArrayList<>();
            for (String s : SENTENCE_END.split(paras.get(body).strip())) if (!s.isBlank()) sentences.add(s.strip());
            sections.add(new ParsedFile.NarrativeSection(m.group(2).strip(), i + 1, body + 1, sentences,
                    paras.get(i).strip(), submitter, submitterLoc));
        }
        return new ParsedFile(name, "docx", new ArrayList<>(), sections);
    }
}
