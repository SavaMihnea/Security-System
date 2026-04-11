package com.securitysystem.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sensor_id")
    private Sensor sensor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EventType eventType;

    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    private boolean resolved = false;

    private LocalDateTime resolvedAt;

    @Column(length = 255)
    private String notes;

    public enum EventType {
        MOTION_DETECTED,
        VIBRATION_DETECTED,
        DOOR_OPENED,
        DOOR_CLOSED,
        ALARM_TRIGGERED,
        ALARM_DISARMED,
        SYSTEM_ARMED,
        SYSTEM_DISARMED,
        NODE_ONLINE,
        NODE_OFFLINE,
        AI_CONVERSATION_STARTED,
        HEARTBEAT
    }
}
