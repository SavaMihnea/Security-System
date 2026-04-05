package com.securitysystem.controller;

import com.securitysystem.dto.SystemStatusDto;
import com.securitysystem.model.SystemConfig;
import com.securitysystem.service.SystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemService systemService;

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
}
