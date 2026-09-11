package com.nagarro.awsdemo.repository;

import com.nagarro.awsdemo.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
}
