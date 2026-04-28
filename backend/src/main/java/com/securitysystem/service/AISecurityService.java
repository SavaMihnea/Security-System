package com.securitysystem.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage 1 orchestrator: generates deterrence audio via OpenAI TTS and pushes
 * it to the ESP32-S3 Hub over WebSocket.
 *
 * Why @Async here and not in AlarmManager:
 *   AlarmManager.executeSecurityAction() runs inside the HTTP handler for
 *   POST /api/esp/event. OpenAI TTS takes 1-3 seconds — blocking that thread
 *   would time out the ESP32. The @Async annotation moves the work off the
 *   request thread immediately, so the HTTP response returns in <10ms.
 *
 * WebSocket flow:
 *   Backend generates MP3 → Base64 encodes → sends START_DETERRANCE to /topic/ai-audio
 *   Hub decodes Base64 → plays through MAX98357A → sends DETERRANCE_COMPLETE to /app/hub/deterrance-complete
 *   Backend receives DETERRANCE_COMPLETE → transitions to Stage 2 (siren)
 *
 * Base64 size note:
 *   A 5-second deterrence phrase at 128kbps ≈ 80 KB MP3 → ≈107 KB Base64.
 *   WebSocketConfig raises the STOMP frame limit to 256 KB to accommodate this.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AISecurityService {

    private final AiService aiService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Generates the opening deterrence phrase as MP3, Base64-encodes it, and
     * broadcasts the START_DETERRANCE WebSocket message to the Hub.
     *
     * If TTS fails (OpenAI down / key missing), the message is still sent with
     * audioBase64=null so the Hub can fall back to a local siren immediately and
     * reply with reason=AUDIO_UNAVAILABLE — which still triggers Stage 2 correctly.
     *
     * Outbound topic: /topic/ai-audio
     *
     * Message structure:
     * {
     *   "command":          "START_DETERRANCE",
     *   "sessionId":        "NODE_AABBCC_1714310400000",
     *   "nodeId":           "NODE_AABBCC",
     *   "sensorName":       "Front Door",
     *   "audioBase64":      "<Base64-encoded MP3, or null on TTS failure>",
     *   "audioLengthBytes": 81920,
     *   "entryDelaySeconds": 30,
     *   "maxDurationSeconds": 120,
     *   "timestamp":        "2026-04-28T10:00:00Z"
     * }
     */
    @Async("aiTaskExecutor")
    public void generateAndBroadcastInitialDeterrence(
            String sessionId,
            String nodeId,
            String sensorName,
            long entryDelaySeconds,
            long maxDurationSeconds) {

        log.info("[AI-SEC] Generating deterrence audio — session={} sensor={}", sessionId, sensorName);

        byte[] mp3Bytes = new byte[0];
        try {
            byte[] result = aiService.generateAlarmStartSpeech(sessionId);
            if (result != null) mp3Bytes = result;
        } catch (Exception e) {
            log.error("[AI-SEC] TTS generation failed for session {}: {}", sessionId, e.getMessage());
        }

        boolean hasAudio = mp3Bytes != null && mp3Bytes.length > 0;
        String audioBase64 = hasAudio ? Base64.getEncoder().encodeToString(mp3Bytes) : null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("command", "START_DETERRANCE");
        payload.put("sessionId", sessionId);
        payload.put("nodeId", nodeId);
        payload.put("sensorName", sensorName);
        payload.put("audioBase64", audioBase64);
        payload.put("audioLengthBytes", hasAudio ? mp3Bytes.length : 0);
        payload.put("entryDelaySeconds", entryDelaySeconds);
        payload.put("maxDurationSeconds", maxDurationSeconds);
        payload.put("timestamp", Instant.now().toString());

        try {
            messagingTemplate.convertAndSend("/topic/ai-audio", payload);
            log.info("[AI-SEC] START_DETERRANCE pushed — session={} audioBytes={}",
                    sessionId, hasAudio ? mp3Bytes.length : 0);
        } catch (Exception e) {
            log.error("[AI-SEC] WebSocket push failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /** Clears the GPT conversation history for this session (called on disarm or timeout). */
    public void clearSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            aiService.clearSession(sessionId);
            log.debug("[AI-SEC] Session cleared: {}", sessionId);
        }
    }
}
