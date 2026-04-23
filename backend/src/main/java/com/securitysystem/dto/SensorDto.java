package com.securitysystem.dto;

import com.securitysystem.model.Sensor;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class SensorDto {

    private Long id;
    private String nodeId;
    private String type;
    private String name;
    private String location;
    private String status;
    private Instant lastSeen;

    public static SensorDto from(Sensor sensor) {
        SensorDto dto = new SensorDto();
        dto.setId(sensor.getId());
        dto.setNodeId(sensor.getNodeId());
        dto.setType(sensor.getType().name());
        dto.setName(sensor.getName());
        dto.setLocation(sensor.getLocation());
        dto.setStatus(sensor.getStatus().name());
        dto.setLastSeen(sensor.getLastSeen());
        return dto;
    }
}
