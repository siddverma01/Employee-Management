package com.emplmgt.repository;

import com.emplmgt.entity.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendanceStatusRepository extends JpaRepository<AttendanceStatus, String> {

    List<AttendanceStatus> findAllByOrderByCodeAsc();
}