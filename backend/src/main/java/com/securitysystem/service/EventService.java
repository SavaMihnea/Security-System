package com.securitysystem.service;

import com.securitysystem.dto.EventDto;
import com.securitysystem.model.Event;
import com.securitysystem.model.Sensor;
import com.securitysystem.model.SystemConfig;
import com.securitysystem.repository.EventRepository;
import com.securitysystem.repository.SensorRepository;
import com.securitysystem.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final SensorRepository sensorRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AlarmManager alarmManager;

    /**
     * Persists a new event and broadcasts it to all connected dashboard clients
     * via WebSocket so the UI updates in real time without polling.
     */
    public EventDto logEvent(String nodeId, Event.EventType eventType, String notes) {
        Event event = new Event();
        sensorRepository.findByNodeId(nodeId).ifPresent(event::setSensor);
        event.setEventType(eventType);
        event.setNotes(notes);

        Event saved = eventRepository.save(event);
        EventDto dto = EventDto.from(saved);

        // Push to /topic/events — dashboard subscribes to this
        try {
            messagingTemplate.convertAndSend("/topic/events", dto);
        } catch (Exception e) {
            // Don't let WebSocket failure prevent event from being saved
        }
        return dto;
    }

    public List<EventDto> getRecentEvents() {
        return eventRepository.findTop50ByOrderByTimestampDesc()
                .stream().map(EventDto::from).toList();
    }

    public List<EventDto> getActiveAlarms() {
        return eventRepository.findByResolvedFalseOrderByTimestampDesc()
                .stream().map(EventDto::from).toList();
    }

    public void resolveEvent(Long id) {
        eventRepository.findById(id).ifPresent(event -> {
            event.setResolved(true);
            event.setResolvedAt(Instant.now());
            eventRepository.save(event);
            // Reset sensor back to ONLINE so it doesn't stay red in the dashboard
            if (event.getSensor() != null) {
                sensorRepository.findByNodeId(event.getSensor().getNodeId()).ifPresent(sensor -> {
                    sensor.setStatus(Sensor.SensorStatus.ONLINE);
                    sensorRepository.save(sensor);
                });
            }
        });
    }

    public int resolveAllActive() {
        List<Event> active = eventRepository.findByResolvedFalseOrderByTimestampDesc();
        Instant now = Instant.now();
        active.forEach(e -> {
            e.setResolved(true);
            e.setResolvedAt(now);
        });
        eventRepository.saveAll(active);
        // Reset all affected sensors back to ONLINE
        active.stream()
                .map(Event::getSensor)
                .filter(s -> s != null)
                .map(s -> s.getNodeId())
                .distinct()
                .forEach(nodeId -> sensorRepository.findByNodeId(nodeId).ifPresent(sensor -> {
                    sensor.setStatus(Sensor.SensorStatus.ONLINE);
                    sensorRepository.save(sensor);
                }));
        return active.size();
    }

    /**
     * Process incoming sensor event through security matrix.
     * This is called when a sensor node sends an event (DOOR_OPENED, MOTION_DETECTED, etc.).
     * 
     * Implements the Master Security Matrix:
     *   - Logs event to database
     *   - Evaluates arm mode + sensor type to determine alerting action
     *   - Activates Stage 1 (AI) and/or Stage 2 (Siren) as needed
     *   - Handles entry delays for doors
     */
    public EventDto processIncomingEvent(String nodeId, String eventTypeStr, String notes) {
        log.info("[EVENT] Incoming: {} from {}", eventTypeStr, nodeId);

        // Get sensor info
        Optional<Sensor> optionalSensor = sensorRepository.findByNodeId(nodeId);
        if (optionalSensor.isEmpty()) {
            log.warn("[EVENT] Unknown node: {}", nodeId);
            return null;
        }

        Sensor sensor = optionalSensor.get();
        
        // Convert event type string to enum
        Event.EventType eventType;
        try {
            eventType = Event.EventType.valueOf(eventTypeStr);
        } catch (IllegalArgumentException e) {
            log.error("[EVENT] Unknown event type: {}", eventTypeStr);
            return null;
        }

        // Get current system state before saving the event so we can set resolved correctly
        SystemConfig config = systemConfigRepository.findById(1L).orElse(new SystemConfig());
        SystemConfig.ArmMode armMode = config.getArmMode();

        // Only apply security matrix if this is an alarm-type event
        boolean isAlarmEvent = eventType == Event.EventType.MOTION_DETECTED
                || eventType == Event.EventType.VIBRATION_DETECTED
                || eventType == Event.EventType.DOOR_OPENED;

        AlarmManager.SecurityAction action = null;
        boolean triggersAlarm = false;
        if (isAlarmEvent) {
            action = alarmManager.evaluateSecurityMatrix(armMode, sensor.getType(), sensor.getName());
            triggersAlarm = action.shouldActivateStage1 || action.shouldActivateStage2;
            log.info("[SECURITY] Action: {} | Arm: {} | Sensor: {} ({})",
                    action.actionType, armMode, sensor.getName(), sensor.getType());
        }

        // Log the event — auto-resolve immediately when no alarm action was triggered
        // (DISARMED state, LOG_ONLY modes) so unarmed events don't show as active alerts.
        Event event = new Event();
        event.setSensor(sensor);
        event.setEventType(eventType);
        event.setNotes(notes);
        if (isAlarmEvent && !triggersAlarm) {
            event.setResolved(true);
            event.setResolvedAt(Instant.now());
        }
        Event saved = eventRepository.save(event);

        if (action != null) {
            alarmManager.executeSecurityAction(action, nodeId, sensor.getName());
        }

        // Broadcast to dashboard
        EventDto dto = EventDto.from(saved);
        try {
            messagingTemplate.convertAndSend("/topic/events", dto);
        } catch (Exception e) {
            log.error("[EVENT] Failed to broadcast", e);
        }

        return dto;
    }
}
