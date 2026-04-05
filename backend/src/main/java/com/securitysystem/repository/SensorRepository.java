package com.securitysystem.repository;

import com.securitysystem.model.Sensor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SensorRepository extends JpaRepository<Sensor, Long> {
    Optional<Sensor> findByNodeId(String nodeId);
    boolean existsByNodeId(String nodeId);
}
