package com.securitysystem.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sensors")
@Getter
@Setter
@NoArgsConstructor
public class Sensor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique hardware identifier for the ESP32-C3 node.
     * We use the node's WiFi MAC address (e.g. "AA:BB:CC:DD:EE:FF").
     */
    @Column(nullable = false, unique = true, length = 30)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SensorType type;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 100)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SensorStatus status = SensorStatus.OFFLINE;

    private Instant lastSeen;

    public enum SensorType {
        MOTION,
        VIBRATION,
        DOOR,
        CENTRAL
    }

    public enum SensorStatus {
        ONLINE,
        OFFLINE,
        TRIGGERED,
        FAULT
    }
}
