package com.securitysystem.controller;

import com.securitysystem.dto.EventDto;
import com.securitysystem.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public ResponseEntity<List<EventDto>> getRecentEvents() {
        return ResponseEntity.ok(eventService.getRecentEvents());
    }

    @GetMapping("/active")
    public ResponseEntity<List<EventDto>> getActiveAlarms() {
        return ResponseEntity.ok(eventService.getActiveAlarms());
    }

    @PutMapping("/{id}/resolve")
    public ResponseEntity<Void> resolveEvent(@PathVariable Long id) {
        eventService.resolveEvent(id);
        return ResponseEntity.ok().build();
    }
}
