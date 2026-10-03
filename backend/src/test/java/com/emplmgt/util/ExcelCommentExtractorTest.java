package com.emplmgt.util;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real workbook stores its remarks as modern threaded comments that have no
 * POI API; this locks in that they are read straight from the OPC package.
 */
class ExcelCommentExtractorTest {

    @Test
    void extractsThreadedCommentsAndAuthorsFromRealWorkbook() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/leave-tracker-2025.xlsx");
             Workbook wb = new XSSFWorkbook(in)) {
            Map<String, Map<String, ExcelCommentExtractor.CellComment>> comments =
                    ExcelCommentExtractor.extract(wb);

            assertThat(comments.size()).isGreaterThanOrEqualTo(20);
            ExcelCommentExtractor.CellComment f5 = comments.get("June'25").get("F5");
            assertThat(f5).isNotNull();
            assertThat(f5.text()).isEqualTo("LO at 1pm , Informed Anoushka");
            assertThat(f5.author()).isEqualTo("Mariyappa, Sahana");
            assertThat(f5.source()).isEqualTo(ExcelCommentExtractor.SOURCE_THREADED);
            assertThat(f5.at()).isEqualTo(java.time.LocalDateTime.of(2025, 6, 1, 0, 54, 7, 760_000_000));

            assertThat(comments.values().stream().flatMap(m -> m.values().stream())
                    .map(ExcelCommentExtractor.CellComment::text))
                    .isNotEmpty()
                    .noneMatch(t -> t.contains("Your version of Excel"));
        }
    }
}
