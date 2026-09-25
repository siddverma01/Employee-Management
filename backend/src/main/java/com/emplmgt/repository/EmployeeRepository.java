package com.emplmgt.repository;

import com.emplmgt.entity.Employee;
import com.emplmgt.entity.EmploymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByUserId(Long userId);

    Optional<Employee> findByEmployeeCodeIgnoreCase(String employeeCode);

    List<Employee> findByEmployeeCodeIn(Collection<String> employeeCodes);

    Optional<Employee> findByEmailIgnoreCase(String email);

    boolean existsByEmployeeCodeIgnoreCase(String employeeCode);

    boolean existsByEmailIgnoreCase(String email);

    @Query("""
        select e from Employee e
        left join e.department d
        where (:search is null or lower(e.fullName) like lower(concat('%', cast(:search as string), '%'))
              or lower(e.employeeCode) like lower(concat('%', cast(:search as string), '%'))
              or lower(e.email) like lower(concat('%', cast(:search as string), '%')))
          and (:status is null or e.employmentStatus = :status)
          and (:departmentId is null or d.id = :departmentId)
        """)
    Page<Employee> search(@Param("search") String search,
                          @Param("status") EmploymentStatus status,
                          @Param("departmentId") Long departmentId,
                          Pageable pageable);

    List<Employee> findByEmploymentStatus(EmploymentStatus status);

    long countByEmploymentStatus(EmploymentStatus status);

    long countByDepartmentId(Long departmentId);

    long countByDepartmentIdAndEmploymentStatus(Long departmentId, EmploymentStatus status);

    @Query("select count(e) from Employee e where e.employmentStatus = com.emplmgt.entity.EmploymentStatus.ACTIVE and e.dateOfJoining is not null")
    long countActiveWithJoiningDate();

    @Query("select e from Employee e left join fetch e.department where (lower(e.fullName) like lower(concat('%', :q, '%')) or lower(e.employeeCode) like lower(concat('%', :q, '%'))) and e.employmentStatus = com.emplmgt.entity.EmploymentStatus.ACTIVE")
    List<Employee> searchByNameOrCode(@Param("q") String q);
}