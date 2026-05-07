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
    private String scheduleArmTime;
    private String scheduleDisarmTime;
    private String scheduleArmMode;

    public static SystemStatusDto from(SystemConfig config) {
        SystemStatusDto dto = new SystemStatusDto();
        dto.setArmed(config.isArmed());
        dto.setArmMode(config.getArmMode().name());
        dto.setLastUpdated(config.getLastUpdated());
        dto.setUpdatedBy(config.getUpdatedBy());
        dto.setScheduleEnabled(config.isScheduleEnabled());
        dto.setScheduleArmTime(config.getScheduleArmTime());
        dto.setScheduleDisarmTime(config.getScheduleDisarmTime());
        dto.setScheduleArmMode(config.getScheduleArmMode() != null
                ? config.getScheduleArmMode().name() : null);
        return dto;
    }
}
