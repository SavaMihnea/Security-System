package com.securitysystem.dto;

import com.securitysystem.model.SystemConfig;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class SystemStatusDto {

    private boolean armed;
    private String armMode;
    private Instant lastUpdated;
    private String updatedBy;
    private boolean panicActive;
    private boolean scheduleEnabled;
    private String scheduleDays;
    private String scheduleNightArmTime;
    private String scheduleNightDisarmTime;
    private String scheduleHomeArmTime;
    private String scheduleHomeDisarmTime;
    private String scheduleAwayArmTime;
    private String scheduleAwayDisarmTime;

    public static SystemStatusDto from(SystemConfig config) {
        SystemStatusDto dto = new SystemStatusDto();
        dto.setArmed(config.isArmed());
        dto.setArmMode(config.getArmMode().name());
        dto.setLastUpdated(config.getLastUpdated());
        dto.setUpdatedBy(config.getUpdatedBy());
        dto.setScheduleEnabled(config.isScheduleEnabled());
        dto.setScheduleDays(config.getScheduleDays());
        dto.setScheduleNightArmTime(config.getScheduleNightArmTime());
        dto.setScheduleNightDisarmTime(config.getScheduleNightDisarmTime());
        dto.setScheduleHomeArmTime(config.getScheduleHomeArmTime());
        dto.setScheduleHomeDisarmTime(config.getScheduleHomeDisarmTime());
        dto.setScheduleAwayArmTime(config.getScheduleAwayArmTime());
        dto.setScheduleAwayDisarmTime(config.getScheduleAwayDisarmTime());
        return dto;
    }
}
