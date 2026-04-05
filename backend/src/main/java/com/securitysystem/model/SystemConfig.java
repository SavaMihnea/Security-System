package com.securitysystem.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Singleton table (always id=1). Holds the current arm/disarm state
 * of the physical security system.
 */
@Entity
@Table(name = "system_config")
@Getter
@Setter
@NoArgsConstructor
public class SystemConfig {

    @Id
    private Long id = 1L;

    @Column(nullable = false)
    private boolean armed = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ArmMode armMode = ArmMode.DISARMED;

    private LocalDateTime lastUpdated = LocalDateTime.now();

    @Column(length = 50)
    private String updatedBy;

    public enum ArmMode {
        /** System off — no sensors processed. */
        DISARMED,

        /**
         * Home day — system "on" but all sensor events are silently ignored.
         * You're home and moving freely; no alerts needed.
         */
        ARMED_HOME,

        /**
         * Home night — all sensors active, NO entry delay.
         * If a door opens at 3am it is immediately an alarm.
         */
        ARMED_HOME_NIGHT,

        /**
         * Away — all sensors active, 10-second entry delay on door events.
         * Gives you time to disarm after arriving home through the front door.
         */
        ARMED_AWAY
    }
}
