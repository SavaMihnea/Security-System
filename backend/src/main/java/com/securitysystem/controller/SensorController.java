package com.securitysystem.controller;

import com.securitysystem.dto.SensorDto;
import com.securitysystem.service.SensorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}
