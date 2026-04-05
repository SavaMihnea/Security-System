package com.securitysystem.service;

import com.securitysystem.dto.SensorDto;
import com.securitysystem.model.Sensor;
import com.securitysystem.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class SensorService {

    private final SensorRepository sensorRepository;

    public List<SensorDto> getAllSensors() {
        return sensorRepository.findAll().stream()
                .map(SensorDto::from)
                .toList();
    }

    public SensorDto getSensor(Long id) {
        return sensorRepository.findById(id)
                .map(SensorDto::from)
                .orElseThrow(() -> new IllegalArgumentException("Sensor not found: " + id));
    }

    /**
     * Called when a node registers itself. Creates a new Sensor record or
     * updates an existing one (identified by nodeId).
     */
    public Sensor registerOrUpdateNode(String nodeId, String name, String location, Sensor.SensorType type) {
        Sensor sensor = sensorRepository.findByNodeId(nodeId).orElseGet(Sensor::new);
        sensor.setNodeId(nodeId);
        sensor.setName(name);
        sensor.setLocation(location);
        sensor.setType(type);
        sensor.setStatus(Sensor.SensorStatus.ONLINE);
        sensor.setLastSeen(LocalDateTime.now());
        return sensorRepository.save(sensor);
    }

    /** Called on every heartbeat or event received from a node. */
    public void markOnline(String nodeId) {
        sensorRepository.findByNodeId(nodeId).ifPresent(sensor -> {
            sensor.setStatus(Sensor.SensorStatus.ONLINE);
            sensor.setLastSeen(LocalDateTime.now());
            sensorRepository.save(sensor);
        });
    }

    public void markOffline(String nodeId) {
        sensorRepository.findByNodeId(nodeId).ifPresent(sensor -> {
            sensor.setStatus(Sensor.SensorStatus.OFFLINE);
            sensorRepository.save(sensor);
        });
    }

    public void markTriggered(String nodeId) {
        sensorRepository.findByNodeId(nodeId).ifPresent(sensor -> {
            sensor.setStatus(Sensor.SensorStatus.TRIGGERED);
            sensor.setLastSeen(LocalDateTime.now());
            sensorRepository.save(sensor);
        });
    }
}
