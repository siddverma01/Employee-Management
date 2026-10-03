package com.emplmgt.util;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads cell comments / notes out of a workbook and keys them by worksheet name
 * and cell reference ("G5").
 *
 * <p>Excel stores two kinds of cell notes. Classic <em>legacy</em> comments live
 * in {@code xl/commentsN.xml} and are fully exposed by POI. Modern
 * <em>threaded</em> comments live in {@code xl/threadedComments/*.xml} and have
 * no POI API at all; {@code Cell.getCellComment()} only returns a
 * "[Threaded comment] ... your version of Excel ..." placeholder for those. To
 * avoid losing the real text, this extractor follows each worksheet's
 * {@code threadedComment} relationship into the raw OPC package and parses the
 * XML directly, resolving the author through {@code xl/persons/person.xml}.</p>
 */
public final class ExcelCommentExtractor {

    private ExcelCommentExtractor() {
    }

    /** Relationship used to hang threaded comments off a worksheet. */
    public static final String THREADED_COMMENT_REL =
            "http://schemas.microsoft.com/office/2017/10/relationships/threadedComment";
    /** Legacy relationship type, some producers emit this older URI instead. */
    private static final String THREADED_COMMENT_REL_2011 =
            "http://schemas.microsoft.com/office/2011/relationships/threadedComment";
    private static final String PERSON_REL =
            "http://schemas.microsoft.com/office/2011/relationships/person";

    public static final String SOURCE_THREADED = "EXCEL_COMMENT";
    public static final String SOURCE_LEGACY = "EXCEL_LEGACY_COMMENT";

    /** Set by POI for a threaded cell; the real text is read from the package. */
    private static final String PLACEHOLDER_MARKER = "threaded comment";

    private static final Pattern PERSONS_PART = Pattern.compile("/xl/persons/.*\\.xml");

    /** One comment's text, author and origin. */
    public record CellComment(String text, String author, String source) {
    }

    /**
     * Extract all comments: {@code sheetName -> cellRef (upper-case, no $) ->
     * comment}. Never throws: an unreadable optional part is skipped so a bad
     * comment can never fail an import.
     */
    public static Map<String, Map<String, CellComment>> extract(Workbook wb) {
        Map<String, Map<String, CellComment>> out = new LinkedHashMap<>();
        if (wb instanceof XSSFWorkbook xwb) {
            Map<String, String> persons = persons(xwb);
            for (int i = 0; i < xwb.getNumberOfSheets(); i++) {
                XSSFSheet sheet = xwb.getSheetAt(i);
                Map<String, CellComment> comments = new LinkedHashMap<>();
                threaded(sheet, persons, comments);
                legacy(sheet, comments);
                if (!comments.isEmpty()) {
                    out.put(sheet.getSheetName(), comments);
                }
            }
        } else {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                Map<String, CellComment> comments = new LinkedHashMap<>();
                legacy(sheet, comments);
                if (!comments.isEmpty()) {
                    out.put(sheet.getSheetName(), comments);
                }
            }
        }
        return out;
    }

    /** person id -> display name from xl/persons/person.xml. */
    private static Map<String, String> persons(XSSFWorkbook wb) {
        Map<String, String> byId = new HashMap<>();
        try {
            PackagePart wbPart = wb.getPackagePart();
            if (wbPart != null) {
                for (PackageRelationship rel : wbPart.getRelationshipsByType(PERSON_REL)) {
                    readPersons(wbPart.getPackage().getPart(rel), byId);
                }
                // Some producers omit the workbook relationship; scan directly.
                for (PackagePart part : wbPart.getPackage().getPartsByName(PERSONS_PART)) {
                    readPersons(part, byId);
                }
            }
        } catch (Exception ignored) {
            // Persons are optional metadata; a failure only means no author names.
        }
        return byId;
    }

    private static void readPersons(PackagePart part, Map<String, String> byId) {
        if (part == null) {
            return;
        }
        try (InputStream in = part.getInputStream()) {
            Document doc = parse(in);
            NodeList nodes = doc.getElementsByTagName("person");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String id = el.getAttribute("id");
                String name = el.getAttribute("displayName");
                if (!id.isBlank() && name != null && !name.isBlank()) {
                    byId.put(id, name);
                }
            }
        } catch (Exception ignored) {
            // ditto
        }
    }

    /** Both the current and the older Office relationship URIs, if present. */
    private static List<PackageRelationship> threadedRels(PackagePart sheetPart) throws Exception {
        List<PackageRelationship> rels = new ArrayList<>();
        for (PackageRelationship rel : sheetPart.getRelationshipsByType(THREADED_COMMENT_REL)) {
            rels.add(rel);
        }
        for (PackageRelationship rel : sheetPart.getRelationshipsByType(THREADED_COMMENT_REL_2011)) {
            rels.add(rel);
        }
        return rels;
    }

    private static void threaded(XSSFSheet sheet, Map<String, String> persons,
                                 Map<String, CellComment> comments) {
        try {
            PackagePart sheetPart = sheet.getPackagePart();
            if (sheetPart == null) {
                return;
            }
            OPCPackage pkg = sheetPart.getPackage();
            for (PackageRelationship rel : threadedRels(sheetPart)) {
                // OPCPackage#getPart(PackageRelationship) can miss parts that were
                // not eagerly loaded; resolve the target part name ourselves.
                PackagePart part = pkg.getPart(PackagingURIHelper.createPartName(
                        PackagingURIHelper.resolvePartUri(sheetPart.getPartName().getURI(), rel.getTargetURI())));
                if (part == null) {
                    continue;
                }
                try (InputStream in = part.getInputStream()) {
                    Document doc = parse(in);
                    NodeList nodes = doc.getElementsByTagName("threadedComment");
                    for (int i = 0; i < nodes.getLength(); i++) {
                        Element el = (Element) nodes.item(i);
                        String ref = el.getAttribute("ref");
                        String text = textOf(el);
                        if (ref == null || ref.isBlank() || text == null || text.isBlank()) {
                            continue;
                        }
                        String author = persons.get(el.getAttribute("personId"));
                        comments.put(normaliseRef(ref),
                                new CellComment(text.trim(), blankToNull(author), SOURCE_THREADED));
                    }
                }
            }
        } catch (Exception ignored) {
            // A malformed threadedComments part must not fail the whole import.
        }
    }

    /** Real (non-placeholder) legacy comments; threaded cells are skipped since
     *  their genuine text was already read from the package. */
    private static void legacy(Sheet sheet, Map<String, CellComment> comments) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                Comment c = cell.getCellComment();
                if (c == null || c.getString() == null) {
                    continue;
                }
                String text = c.getString().getString();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (isThreadedPlaceholder(text)) {
                    continue;
                }
                String ref = normaliseRef(new CellReference(cell).formatAsString());
                comments.putIfAbsent(ref, new CellComment(text.trim(), blankToNull(c.getAuthor()), SOURCE_LEGACY));
            }
        }
    }

    private static String textOf(Element threadedComment) {
        NodeList texts = threadedComment.getElementsByTagName("text");
        if (texts.getLength() > 0 && texts.item(0) != null) {
            return texts.item(0).getTextContent();
        }
        return null;
    }

    private static boolean isThreadedPlaceholder(String text) {
        return text.toLowerCase(Locale.ROOT).contains(PLACEHOLDER_MARKER)
                || text.trim().startsWith("[Threaded comment]");
    }

    private static String normaliseRef(String ref) {
        if (ref == null) {
            return null;
        }
        // CellReference(Cell) yields "Sheet!C2"; callers key by bare "C2".
        int bang = ref.lastIndexOf('!');
        String cell = bang >= 0 ? ref.substring(bang + 1) : ref;
        return cell.replace("$", "").toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Document parse(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // Feature unsupported by this parser; the OPC parts are producer-trusted.
        }
        return factory.newDocumentBuilder().parse(in);
    }
}
