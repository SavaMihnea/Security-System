package com.securitysystem.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

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

    private Instant lastUpdated = Instant.now();

    @Column(length = 50)
    private String updatedBy;

    // Schedule: optional daily arm/disarm times (stored as "HH:mm" strings, null = disabled)
    @Column(length = 5)
    private String scheduleArmTime;      // e.g. "22:30" — arm at this time daily

    @Column(length = 5)
    private String scheduleDisarmTime;   // e.g. "07:00" — disarm at this time daily

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ArmMode scheduleArmMode = ArmMode.ARMED_HOME_NIGHT; // mode to use when auto-arming

    @Column(nullable = false)
    private boolean scheduleEnabled = false;

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
