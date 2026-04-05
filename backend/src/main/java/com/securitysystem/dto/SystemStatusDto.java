package com.securitysystem.dto;

import com.securitysystem.model.SystemConfig;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SystemStatusDto {

    private boolean armed;
    private String armMode;
    private LocalDateTime lastUpdated;
    private String updatedBy;

    public static SystemStatusDto from(SystemConfig config) {
        SystemStatusDto dto = new SystemStatusDto();
        dto.setArmed(config.isArmed());
        dto.setArmMode(config.getArmMode().name());
        dto.setLastUpdated(config.getLastUpdated());
        dto.setUpdatedBy(config.getUpdatedBy());
        return dto;
    }
}
