package com.securitysystem.controller;

import com.securitysystem.dto.SystemStatusDto;
import com.securitysystem.model.SystemConfig;
import com.securitysystem.service.AiService;
import com.securitysystem.service.SystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemService systemService;
    private final AiService aiService;

    @GetMapping("/status")
    public ResponseEntity<SystemStatusDto> getStatus() {
        return ResponseEntity.ok(systemService.getStatus());
    }

    /**
     * Arm the system.
     * @param mode ARMED_HOME (motion sensors off, door/vibration active)
     *             or ARMED_AWAY (all sensors active) — default: ARMED_AWAY
     */
    @PostMapping("/arm")
    public ResponseEntity<SystemStatusDto> arm(
            @RequestParam(defaultValue = "ARMED_AWAY") String mode,
            @AuthenticationPrincipal String username) {

        SystemConfig.ArmMode armMode = SystemConfig.ArmMode.valueOf(mode);
        return ResponseEntity.ok(systemService.setArmMode(armMode, username));
    }

    @PostMapping("/disarm")
    public ResponseEntity<SystemStatusDto> disarm(@AuthenticationPrincipal String username) {
        return ResponseEntity.ok(systemService.setArmMode(SystemConfig.ArmMode.DISARMED, username));
    }

    @PostMapping("/schedule")
    public ResponseEntity<?> setSchedule(
            @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(systemService.setSchedule(
                    Boolean.parseBoolean(body.getOrDefault("scheduleEnabled", "false")),
                    body.get("scheduleDays"),
                    body.get("scheduleNightArmTime"),
                    body.get("scheduleNightDisarmTime"),
                    body.get("scheduleHomeArmTime"),
                    body.get("scheduleHomeDisarmTime"),
                    body.get("scheduleAwayArmTime"),
                    body.get("scheduleAwayDisarmTime")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid schedule data"));
        }
    }

    @PostMapping("/panic")
    public ResponseEntity<SystemStatusDto> panic(@AuthenticationPrincipal String username) {
        return ResponseEntity.ok(systemService.triggerPanic(username));
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> getDiagnostics() {
        Map<String, Object> result = new HashMap<>();
        String aiStatus = !aiService.isConfigured() ? "OFFLINE"
                        : aiService.getActiveSessionCount() > 0 ? "BUSY"
                        : "READY";
        result.put("aiStatus", aiStatus);
        return ResponseEntity.ok(result);
    }
}
