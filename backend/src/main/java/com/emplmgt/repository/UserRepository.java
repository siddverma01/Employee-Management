package com.emplmgt.repository;

import com.emplmgt.entity.Role;
import com.emplmgt.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findByRole(Role role);

    @Modifying
    @Query("update User u set u.lastLoginAt = :now where u.id = :id")
    void updateLastLogin(@Param("id") Long id, @Param("now") Instant now);
}