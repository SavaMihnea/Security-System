package com.securitysystem.controller;

import com.securitysystem.dto.SensorDto;
import com.securitysystem.model.Sensor;
import com.securitysystem.service.SensorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sensors")
@RequiredArgsConstructor
public class SensorController {

    private final SensorService sensorService;

    @GetMapping
    public ResponseEntity<List<SensorDto>> getAllSensors() {
        return ResponseEntity.ok(sensorService.getAllSensors());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SensorDto> getSensor(@PathVariable Long id) {
        return ResponseEntity.ok(sensorService.getSensor(id));
    }

    @PostMapping("/simulate")
    public ResponseEntity<SensorDto> simulateSensor() {
        String[] names     = {"Front Door", "Back Door", "Garage Motion", "Hallway Motion", "Window Vibration", "Patio Door", "Office Motion"};
        String[] locations = {"Front Entrance", "Back Entrance", "Garage", "Hallway", "Living Room", "Patio", "Office"};
        Sensor.SensorType[] types = {Sensor.SensorType.DOOR, Sensor.SensorType.DOOR, Sensor.SensorType.MOTION, Sensor.SensorType.MOTION, Sensor.SensorType.VIBRATION, Sensor.SensorType.DOOR, Sensor.SensorType.MOTION};
        int idx = (int)(Math.random() * names.length);
        String nodeId = "SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Sensor sensor = sensorService.registerOrUpdateNode(nodeId, names[idx], locations[idx], types[idx]);
        return ResponseEntity.ok(SensorDto.from(sensor));
    }
}
