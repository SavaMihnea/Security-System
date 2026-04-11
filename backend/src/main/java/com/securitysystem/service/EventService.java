package com.securitysystem.service;

import com.securitysystem.dto.EventDto;
import com.securitysystem.model.Event;
import com.securitysystem.repository.EventRepository;
import com.securitysystem.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class EventService {

    private final EventRepository eventRepository;
    private final SensorRepository sensorRepository;
    private final SimpMessagingTemplate messagingTemplate;

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
            event.setResolvedAt(java.time.LocalDateTime.now());
            eventRepository.save(event);
        });
    }
}
