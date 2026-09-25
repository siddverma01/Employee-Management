package com.emplmgt.repository;

import com.emplmgt.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("select n from Notification n where n.user.id = :userId or n.user is null order by n.createdAt desc")
    Page<Notification> findAllForUser(@Param("userId") Long userId, Pageable pageable);

    @Query("select count(n) from Notification n where (n.user.id = :userId or n.user is null) and n.readAt is null")
    long countUnreadForUser(@Param("userId") Long userId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where (n.user.id = :userId or n.user is null) and n.readAt is null")
    int markAllReadForUser(@Param("userId") Long userId, @Param("now") Instant now);

    @Query("select n from Notification n where n.user is null and n.createdAt > :since")
    List<Notification> findByBroadcastAfter(@Param("since") Instant since);

    @Query("select case when count(n) > 0 then true else false end from Notification n where n.user is null and n.body like %:marker%")
    boolean existsBroadcastLike(@Param("marker") String marker);
}