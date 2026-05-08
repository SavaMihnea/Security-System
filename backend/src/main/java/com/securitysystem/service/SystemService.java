package com.securitysystem.service;

import com.securitysystem.dto.EventDto;
import com.securitysystem.dto.SystemStatusDto;
import com.securitysystem.model.Event;
import com.securitysystem.model.SystemConfig;
import com.securitysystem.repository.EventRepository;
import com.securitysystem.repository.SystemConfigRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@SuppressWarnings("null")
public class SystemService {

    private final SystemConfigRepository systemConfigRepository;
    private final EventRepository eventRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AiService aiService;
    private final AlarmManager alarmManager;

    private final AtomicBoolean panicPending = new AtomicBoolean(false);

    public SystemService(SystemConfigRepository systemConfigRepository,
                         EventRepository eventRepository,
                         SimpMessagingTemplate messagingTemplate,
                         AiService aiService,
                         @Lazy AlarmManager alarmManager) {
        this.systemConfigRepository = systemConfigRepository;
        this.eventRepository        = eventRepository;
        this.messagingTemplate      = messagingTemplate;
        this.aiService              = aiService;
        this.alarmManager           = alarmManager;
    }

    public SystemStatusDto getStatus() {
        SystemStatusDto dto = SystemStatusDto.from(getConfig());
        dto.setPanicActive(panicPending.get());
        return dto;
    }

    public SystemStatusDto triggerPanic(String username) {
        panicPending.set(true);

        Event trigger = new Event();
        trigger.setEventType(Event.EventType.ALARM_TRIGGERED);
        trigger.setNotes("PANIC triggered by " + username);
        eventRepository.save(trigger);
        try { messagingTemplate.convertAndSend("/topic/events", EventDto.from(trigger)); }
        catch (Exception ignored) {}

        // Send ACTIVATE_SIREN to the hub so the relay stays on until explicitly stopped
        alarmManager.activateSiren();

        Event sirenEvent = new Event();
        sirenEvent.setEventType(Event.EventType.SIREN_ACTIVE);
        sirenEvent.setNotes("Manual panic by " + username);
        eventRepository.save(sirenEvent);
        try { messagingTemplate.convertAndSend("/topic/events", EventDto.from(sirenEvent)); }
        catch (Exception ignored) {}

        return getStatus();
    }

    public void clearPanic() {
        panicPending.set(false);
    }

    @Transactional
    public SystemStatusDto setSchedule(boolean enabled, String days,
                                       String nightArm, String nightDisarm,
                                       String homeArm,  String homeDisarm,
                                       String awayArm,  String awayDisarm) {
        SystemConfig config = getConfig();
        config.setScheduleEnabled(enabled);
        config.setScheduleDays(days);
        config.setScheduleNightArmTime(nightArm);
        config.setScheduleNightDisarmTime(nightDisarm);
        config.setScheduleHomeArmTime(homeArm);
        config.setScheduleHomeDisarmTime(homeDisarm);
        config.setScheduleAwayArmTime(awayArm);
        config.setScheduleAwayDisarmTime(awayDisarm);
        SystemConfig saved = systemConfigRepository.save(config);
        SystemStatusDto dto = SystemStatusDto.from(saved);
        dto.setPanicActive(panicPending.get());
        return dto;
    }

    @Transactional
    public SystemStatusDto setArmMode(SystemConfig.ArmMode mode, String updatedBy) {
        SystemConfig config = getConfig();
        config.setArmed(mode != SystemConfig.ArmMode.DISARMED);
        config.setArmMode(mode);
        config.setLastUpdated(Instant.now());
        config.setUpdatedBy(updatedBy);

        SystemConfig saved = systemConfigRepository.save(config);
        SystemStatusDto dto = SystemStatusDto.from(saved);

        boolean alarmWasActive = false;
        if (mode == SystemConfig.ArmMode.DISARMED) {
            // Check before clearing panicPending
            alarmWasActive = panicPending.get() ||
                eventRepository.findByResolvedFalseOrderByTimestampDesc().stream()
                    .anyMatch(e -> e.getEventType() == Event.EventType.ALARM_TRIGGERED
                               || e.getEventType() == Event.EventType.SIREN_ACTIVE);
            aiService.clearAllSessions();
            panicPending.set(false);
            // Always send DEACTIVATE_SIREN so the hub relay turns off regardless of how it was triggered
            alarmManager.deactivateSiren();
        }

        // Log arm/disarm as an Event for the audit trail
        Event event = new Event();
        event.setEventType(mode == SystemConfig.ArmMode.DISARMED
                ? Event.EventType.SYSTEM_DISARMED
                : Event.EventType.SYSTEM_ARMED);
        event.setNotes(mode.name() + " by " + updatedBy);
        eventRepository.save(event);
        try {
            messagingTemplate.convertAndSend("/topic/events", EventDto.from(event));
        } catch (Exception ignored) {}

        // Log ALARM_DISARMED after SYSTEM_DISARMED so it appears first (newest) in the events list
        if (alarmWasActive) {
            Event stopEvent = new Event();
            stopEvent.setEventType(Event.EventType.ALARM_DISARMED);
            stopEvent.setNotes("Alarm stopped by " + updatedBy);
            eventRepository.save(stopEvent);
            try {
                messagingTemplate.convertAndSend("/topic/events", EventDto.from(stopEvent));
            } catch (Exception ignored) {}
        }

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
