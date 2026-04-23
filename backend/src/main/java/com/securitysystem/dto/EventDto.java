package com.securitysystem.dto;

import com.securitysystem.model.Event;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class EventDto {

    private Long id;
    private Long sensorId;
    private String sensorName;
    private String eventType;
    private Instant timestamp;
    private boolean resolved;
    private Instant resolvedAt;
    private String notes;

    public static EventDto from(Event event) {
        EventDto dto = new EventDto();
        dto.setId(event.getId());
        if (event.getSensor() != null) {
            dto.setSensorId(event.getSensor().getId());
            dto.setSensorName(event.getSensor().getName());
        }
        dto.setEventType(event.getEventType().name());
        dto.setTimestamp(event.getTimestamp());
        dto.setResolved(event.isResolved());
        dto.setResolvedAt(event.getResolvedAt());
        dto.setNotes(event.getNotes());
        return dto;
    }
}
