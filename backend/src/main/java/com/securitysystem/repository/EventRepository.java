package com.securitysystem.repository;

import com.securitysystem.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findTop50ByOrderByTimestampDesc();
    List<Event> findBySensorIdOrderByTimestampDesc(Long sensorId);
    List<Event> findByResolvedFalseOrderByTimestampDesc();
}
