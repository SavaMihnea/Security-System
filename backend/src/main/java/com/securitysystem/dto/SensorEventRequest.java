package com.securitysystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Sent by ESP32 nodes when reporting a sensor event or a heartbeat.
 * eventType is nullable — heartbeats don't need it.
 */
@Getter
@Setter
public class SensorEventRequest {

    @NotBlank
    private String nodeId;

    private String eventType;

    private String notes;
}
