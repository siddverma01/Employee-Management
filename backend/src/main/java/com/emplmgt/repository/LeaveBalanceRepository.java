package com.emplmgt.repository;

import com.emplmgt.entity.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    Optional<LeaveBalance> findByEmployeeIdAndLeaveTypeAndYear(Long employeeId, com.emplmgt.entity.LeaveType leaveType, Integer year);

    List<LeaveBalance> findByEmployeeIdAndYear(Long employeeId, Integer year);

    List<LeaveBalance> findByEmployeeIdOrderByYearDesc(Long employeeId);
}