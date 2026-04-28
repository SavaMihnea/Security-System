package com.securitysystem.controller;

import com.securitysystem.service.AlarmManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.Map;

/**
 * WebSocket controller for ESP32-S3 Hub ↔ Backend real-time coordination.
 *
 * Stage 1 → Stage 2 handoff flow:
 *   1. Sensor triggers → AlarmManager.startStage1AI()
 *   2. AISecurityService generates MP3 → sends START_DETERRANCE to /topic/ai-audio
 *   3. Hub receives audio, plays it through MAX98357A (I2S bus occupied)
 *   4. Hub finishes playback, closes I2S stream, sends DETERRANCE_COMPLETE here
 *   5. handleDeterranceComplete() → alarmManager.transitionToStage2()
 *   6. Backend sends ACTIVATE_SIREN to /topic/siren-control
 *   7. Hub activates relay (GPIO 10) — I2S is free, no hardware conflict
 *
 * WebSocket message destinations (Hub → Backend use /app prefix):
 *   /app/hub/deterrance-complete  — primary Stage 1 completion signal
 *   /app/hub/ai-complete          — legacy alias, delegates to deterrance-complete
 *   /app/hub/entry-delay-expired  — Hub signals entry delay countdown finished
 *   /app/hub/siren-status         — Hub confirms relay state
 *   /app/hub/heartbeat            — Hub liveness ping
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class HubCommunicationController {

    private final AlarmManager alarmManager;

    /**
     * Primary Stage 1 → Stage 2 gate.
     *
     * The Hub MUST send this after fully closing its I2S audio stream.
     * Only then will the siren relay be activated, preventing bus conflicts.
     *
     * Inbound message structure:
     * {
     *   "command":         "DETERRANCE_COMPLETE",
     *   "sessionId":       "NODE_XXXXXX_1714310400000",
     *   "nodeId":          "NODE_XXXXXX",
     *   "reason":          "VOICE_FINISHED" | "USER_DISARMED" | "TIMEOUT" | "AUDIO_UNAVAILABLE",
     *   "durationSeconds": 8,
     *   "timestamp":       "2026-04-28T10:00:08Z"
     * }
     *
     * reason semantics:
     *   VOICE_FINISHED     — audio played fully, intruder did not disarm → activate siren
     *   TIMEOUT            — Hub's local maxDurationSeconds elapsed → activate siren
     *   AUDIO_UNAVAILABLE  — audioBase64 was null (TTS failed) → activate siren immediately
     *   USER_DISARMED      — user disarmed during playback → cancel alarm, no siren
     */
    @MessageMapping("/hub/deterrance-complete")
    public void handleDeterranceComplete(@Payload Map<String, Object> payload) {
        String nodeId = (String) payload.get("nodeId");
        String reason = (String) payload.get("reason");

        log.info("[HUB] DETERRANCE_COMPLETE — node={} reason={}", nodeId, reason);

        if ("USER_DISARMED".equals(reason)) {
            alarmManager.cancelEntryDelayAndAlarm(nodeId);
        } else {
            alarmManager.transitionToStage2(nodeId);
        }
    }

    /**
     * Legacy endpoint — kept for backward compatibility with hub firmware
     * that still sends /app/hub/ai-complete. Delegates to the same logic.
     */
    @MessageMapping("/hub/ai-complete")
    public void handleAICompletion(@Payload Map<String, Object> payload) {
        log.debug("[HUB] ai-complete received (legacy) — delegating to deterrance-complete handler");
        handleDeterranceComplete(payload);
    }

    /**
     * Hub signals that the entry delay countdown has expired on its side.
     * If Stage 1 is still active, transition to siren now.
     *
     * Inbound message structure:
     * {
     *   "nodeId":    "NODE_XXXXXX",
     *   "event":     "ENTRY_DELAY_EXPIRED",
     *   "timestamp": "2026-04-28T10:00:30Z"
     * }
     */
    @MessageMapping("/hub/entry-delay-expired")
    public void handleEntryDelayExpired(@Payload Map<String, Object> payload) {
        String nodeId = (String) payload.get("nodeId");
        log.warn("[HUB] Entry delay EXPIRED for {}", nodeId);

        if (alarmManager.isEntryDelayActive(nodeId)) {
            alarmManager.transitionToStage2(nodeId);
        }
    }

    /**
     * Hub confirms relay state after receiving ACTIVATE_SIREN / DEACTIVATE_SIREN.
     *
     * Inbound message structure:
     * {
     *   "status":    "ACTIVATED" | "DEACTIVATED",
     *   "timestamp": "2026-04-28T10:00:08Z"
     * }
     */
    @MessageMapping("/hub/siren-status")
    public void handleSirenStatus(@Payload Map<String, Object> payload) {
        String status = (String) payload.get("status");
        log.info("[HUB] Siren status: {}", status);
    }

    /**
     * Hub liveness heartbeat.
     *
     * Inbound message structure:
     * { "timestamp": <epoch millis> }
     */
    @MessageMapping("/hub/heartbeat")
    public void handleHubHeartbeat(@Payload Map<String, Object> payload) {
        long timestamp = ((Number) payload.get("timestamp")).longValue();
        log.debug("[HUB] Heartbeat at {}", timestamp);
    }
}
