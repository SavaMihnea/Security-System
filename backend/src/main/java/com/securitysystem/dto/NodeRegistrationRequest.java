package com.securitysystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Sent by a sensor node when registering itself with the backend
 * (via the central ESP32-S3 unit).
 */
@Getter
@Setter
public class NodeRegistrationRequest {

    @NotBlank
    private String nodeId;

    @NotBlank
    private String name;

    private String location;

    /** Matches Sensor.SensorType: MOTION, VIBRATION, DOOR, CENTRAL */
    @NotBlank
    private String type;
}
