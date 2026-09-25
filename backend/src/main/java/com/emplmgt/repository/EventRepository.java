package com.emplmgt.repository;

import com.emplmgt.entity.Event;
import com.emplmgt.entity.ScopeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByEventDateBetweenOrderByEventDate(LocalDate from, LocalDate to);

    @Query("select e from Event e where e.eventDate >= :from and e.eventDate <= :to " +
            "and (e.scope = com.emplmgt.entity.ScopeType.GLOBAL " +
            "     or (e.scope = com.emplmgt.entity.ScopeType.TEAM and (:teamId is null or e.team.id = :teamId)))")
    List<Event> findVisibleInRange(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                   @Param("teamId") Long teamId);

    @Query("select e from Event e where e.eventDate >= :from and e.eventDate <= :to " +
            "and (:scope is null or e.scope = :scope) " +
            "and (:scope is null or :scope <> com.emplmgt.entity.ScopeType.TEAM or :teamId is null or e.team.id = :teamId)")
    List<Event> findInRangeScoped(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                  @Param("scope") ScopeType scope, @Param("teamId") Long teamId);
}