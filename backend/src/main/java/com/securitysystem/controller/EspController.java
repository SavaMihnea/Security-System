package com.securitysystem.controller;

import com.securitysystem.dto.NodeRegistrationRequest;
import com.securitysystem.dto.SensorEventRequest;
import com.securitysystem.dto.SystemStatusDto;
import com.securitysystem.model.Event;
import com.securitysystem.model.Sensor;
import com.securitysystem.service.EventService;
import com.securitysystem.service.SensorService;
import com.securitysystem.service.SystemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints called exclusively by the ESP32 central unit (ESP32-S3).
 * Authentication is via the X-ESP-Key header (shared secret in application.properties).
 * These endpoints are open in SecurityConfig and validated here instead.
 */
@RestController
@RequestMapping("/api/esp")
@RequiredArgsConstructor
public class EspController {

    private final SensorService sensorService;
    private final EventService eventService;
    private final SystemService systemService;

    @Value("${esp.api-key}")
    private String espApiKey;

    /**
     * Called every 30 seconds by the central unit.
     * Returns current arm/disarm state so the ESP32 stays in sync.
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(
            @RequestHeader("X-ESP-Key") String apiKey,
            @Valid @RequestBody SensorEventRequest request) {

        if (!espApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).build();
        }
        sensorService.markOnline(request.getNodeId());
        SystemStatusDto status = systemService.getStatus();
        return ResponseEntity.ok(status);
    }

    /**
     * Called when a sensor node detects a trigger.
     * The central unit relays the event here.
     * Applies the security matrix to determine alerting action.
     * Response includes current system status so ESP32 can act on arm state changes.
     */
    @PostMapping("/event")
    public ResponseEntity<?> receiveEvent(
            @RequestHeader("X-ESP-Key") String apiKey,
            @Valid @RequestBody SensorEventRequest request) {

        if (!espApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).build();
        }

        // Null or blank eventType → treat as a simple status update
        if (request.getEventType() == null || request.getEventType().isBlank()) {
            sensorService.markOnline(request.getNodeId());
            return ResponseEntity.ok(systemService.getStatus());
        }

        try {
            // Process event through security matrix (handles logging and alerting)
            eventService.processIncomingEvent(
                    request.getNodeId(),
                    request.getEventType(),
                    request.getNotes());

            // Mark sensor status — only TRIGGERED when the system is actually armed;
            // unarmed triggers (test fires, accidental bumps) stay ONLINE in the UI.
            boolean isAlarmEvent = request.getEventType().equals("MOTION_DETECTED")
                    || request.getEventType().equals("VIBRATION_DETECTED")
                    || request.getEventType().equals("DOOR_OPENED");

            if (isAlarmEvent && systemService.getStatus().isArmed()) {
                sensorService.markTriggered(request.getNodeId());
            } else {
                sensorService.markOnline(request.getNodeId());
            }

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown event type: " + request.getEventType()));
        }

        return ResponseEntity.ok(systemService.getStatus());
    }

    /**
     * Called once on boot by each node to register its presence in the database.
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerNode(
            @RequestHeader("X-ESP-Key") String apiKey,
            @Valid @RequestBody NodeRegistrationRequest request) {

        if (!espApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).build();
        }

        try {
            Sensor.SensorType type = Sensor.SensorType.valueOf(request.getType());
            Sensor sensor = sensorService.registerOrUpdateNode(
                    request.getNodeId(), request.getName(), request.getLocation(), type);
            eventService.logEvent(sensor.getNodeId(), Event.EventType.NODE_ONLINE, "Node registered/reconnected");
            return ResponseEntity.ok(Map.of("message", "Registered", "nodeId", sensor.getNodeId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid sensor type: " + request.getType()));
        }
    }
}
