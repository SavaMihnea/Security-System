package com.securitysystem.service;

import com.securitysystem.dto.SystemStatusDto;
import com.securitysystem.model.Event;
import com.securitysystem.model.SystemConfig;
import com.securitysystem.repository.EventRepository;
import com.securitysystem.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class SystemService {

    private final SystemConfigRepository systemConfigRepository;
    private final EventRepository eventRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public SystemStatusDto getStatus() {
        return SystemStatusDto.from(getConfig());
    }

    @Transactional
    public SystemStatusDto setArmMode(SystemConfig.ArmMode mode, String updatedBy) {
        SystemConfig config = getConfig();
        config.setArmed(mode != SystemConfig.ArmMode.DISARMED);
        config.setArmMode(mode);
        config.setLastUpdated(LocalDateTime.now());
        config.setUpdatedBy(updatedBy);

        SystemConfig saved = systemConfigRepository.save(config);
        SystemStatusDto dto = SystemStatusDto.from(saved);

        // Log arm/disarm as an Event for the audit trail
        Event event = new Event();
        event.setEventType(mode == SystemConfig.ArmMode.DISARMED
                ? Event.EventType.SYSTEM_DISARMED
                : Event.EventType.SYSTEM_ARMED);
        event.setNotes(mode.name() + " by " + updatedBy);
        eventRepository.save(event);

        // Push status change to dashboard
        try {
            messagingTemplate.convertAndSend("/topic/system-status", dto);
        } catch (Exception e) {
            // Don't let WebSocket failure prevent state change from being saved
        }
        return dto;
    }

    /** Returns the singleton config row, creating it on first call. */
    private SystemConfig getConfig() {
        return systemConfigRepository.findById(1L).orElseGet(() -> {
            SystemConfig config = new SystemConfig();
            return systemConfigRepository.save(config);
        });
    }
}
