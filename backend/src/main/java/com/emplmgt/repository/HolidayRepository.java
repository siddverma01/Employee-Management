package com.emplmgt.repository;

import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.HolidayType;
import com.emplmgt.entity.ScopeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findByHolidayDateBetweenOrderByHolidayDate(LocalDate from, LocalDate to);

    List<Holiday> findByHolidayDateBetween(LocalDate from, LocalDate to);

    @Query("select h from Holiday h where h.holidayDate >= :from and h.holidayDate <= :to and (:country is null or h.country = :country)")
    List<Holiday> findInRange(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("country") String country);

    @Query("select h from Holiday h where h.holidayDate >= :from and h.holidayDate <= :to " +
            "and (:country is null or h.country = :country) " +
            "and (h.scope = com.emplmgt.entity.ScopeType.GLOBAL " +
            "     or (h.scope = com.emplmgt.entity.ScopeType.TEAM and (:teamId is null or h.team.id = :teamId)))")
    List<Holiday> findVisibleInRange(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                     @Param("country") String country, @Param("teamId") Long teamId);

    @Query("select h from Holiday h where h.holidayDate >= :from and h.holidayDate <= :to " +
            "and (:country is null or h.country = :country) " +
            "and (:scope is null or h.scope = :scope) " +
            "and (:scope is null or :scope <> com.emplmgt.entity.ScopeType.TEAM or :teamId is null or h.team.id = :teamId)")
    List<Holiday> findInRangeScoped(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                    @Param("country") String country,
                                    @Param("scope") ScopeType scope, @Param("teamId") Long teamId);

    List<Holiday> findByHolidayDate(LocalDate date);

    List<Holiday> findByHolidayType(HolidayType holidayType);

    List<Holiday> findByHolidayTypeAndActiveTrueOrderByHolidayDate(HolidayType holidayType);

    Optional<Holiday> findByHolidayDateAndCountryAndName(LocalDate date, String country, String name);

    long countByHolidayDateBetween(LocalDate from, LocalDate to);
}