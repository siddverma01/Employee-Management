package com.emplmgt.repository;

import com.emplmgt.entity.AttendanceImportRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AttendanceImportRowRepository extends JpaRepository<AttendanceImportRow, Long> {

    /**
     * The one worklist query behind the Making step. {@code state} picks the review
     * bucket: {@code PENDING} (default) are the cells still awaiting a manual
     * decision, {@code CORRECTED} and {@code SKIPPED} are the completed ones, and
     * {@code ALL} returns every flagged cell regardless of decision. The parse-time
     * issue is deliberately retained on corrected/skipped rows, so they stay visible
     * here for audit instead of disappearing once handled.
     */
    String REVIEW_FILTER = """
            r.importHistory.id = :importId
              and (
                   (:state = 'PENDING' and r.issue is not null and r.corrected = false and r.skipped = false)
                or (:state = 'CORRECTED' and r.corrected = true)
                or (:state = 'SKIPPED' and r.skipped = true)
                or (:state = 'ALL' and r.issue is not null))
              and (:search is null
                   or lower(r.employeeId) like :search
                   or lower(r.employeeName) like :search
                   or lower(r.sheetName) like :search
                   or lower(r.originalStatus) like :search
                   or lower(r.issue) like :search)
              and (:category is null
                   or (:category = 'DUPLICATE' and r.action = 'DUPLICATE')
                   or (:category = 'DATE_MISMATCH' and (r.attendanceDate is null or r.action = 'INVALID'))
                   or (:category = 'MISSING_DATA' and (r.incomingStatus is null or r.incomingStatus = ''))
                   or (:category = 'UNMAPPED' and r.isUnknown = true)
                   or (:category = 'INVALID' and r.action <> 'DUPLICATE'
                       and r.attendanceDate is not null and r.incomingStatus is not null
                       and r.isUnknown = false))
            """;

    @Query("select r from AttendanceImportRow r where " + REVIEW_FILTER + " order by r.id asc")
    Page<AttendanceImportRow> findReviewFiltered(@Param("importId") Long importId,
                                                 @Param("state") String state,
                                                 @Param("search") String search,
                                                 @Param("category") String category,
                                                 Pageable pageable);

    @Query("select r from AttendanceImportRow r where " + REVIEW_FILTER + " order by r.id asc")
    List<AttendanceImportRow> findReviewFilteredList(@Param("importId") Long importId,
                                                     @Param("state") String state,
                                                     @Param("search") String search,
                                                     @Param("category") String category);

    List<AttendanceImportRow> findByImportHistoryIdOrderByIdAsc(Long importId);

    List<AttendanceImportRow> findByImportHistoryIdAndActionInOrderByIdAsc(Long importId, List<String> actions);

    Page<AttendanceImportRow> findByImportHistoryId(Long importId, Pageable pageable);

    long countByImportHistoryId(Long importId);

    long countByImportHistoryIdAndAction(Long importId, String action);

    long countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsFalseAndSkippedIsFalse(Long importId);

    long countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsTrue(Long importId);

    long countByImportHistoryIdAndSkippedIsTrue(Long importId);

    long countByImportHistoryIdAndIssueIsNotNull(Long importId);
}