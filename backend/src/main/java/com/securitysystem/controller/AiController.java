package com.securitysystem.controller;

import com.securitysystem.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @Value("${esp.api-key}")
    private String espApiKey;

    /**
     * Step 1 of the AI conversation pipeline.
     * Called immediately when the alarm triggers — no mic recording needed yet.
     * Returns MP3 bytes of a random deterrence phrase to play through the speaker.
     * Also seeds the GPT-4o conversation history for that session.
     */
    @PostMapping(value = "/alarm-start", produces = "audio/mpeg")
    public ResponseEntity<byte[]> alarmStart(
            @RequestHeader("X-ESP-Key") String apiKey,
            @RequestHeader("X-Session-Id") String sessionId) {

        if (!espApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).build();
        }

        byte[] audio = aiService.generateAlarmStartSpeech(sessionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(audio);
    }

    /**
     * Step 2b — called when the mic detects silence (no intruder speech).
     * Returns a proactive statement to keep psychological pressure up.
     */
    @PostMapping(value = "/proactive", produces = "audio/mpeg")
    public ResponseEntity<byte[]> proactiveStatement(
            @RequestHeader("X-ESP-Key") String apiKey,
            @RequestHeader("X-Session-Id") String sessionId) {

        if (!espApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).build();
        }

        byte[] audio = aiService.generateProactiveStatement(sessionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(audio);
    }

    /**
     * Step 2a — called when the mic records intruder speech.
     * Full pipeline: WAV → Whisper → GPT-4o → TTS → MP3.
     *
     * Flow:
     *   1. ESP32 records intruder audio and POSTs raw WAV bytes here
     *   2. Backend transcribes audio → generates AI response → synthesizes speech
     *   3. Returns MP3 bytes for the ESP32 to play through the speaker
     *
     * X-Session-Id: unique ID per intrusion event (e.g. alarm timestamp)
     *               allows multi-turn conversation history
     */
    @PostMapping(
        value = "/respond",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
        produces = "audio/mpeg"
    )
    public ResponseEntity<byte[]> respondToAudio(
            @RequestHeader("X-ESP-Key") String apiKey,
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestBody byte[] audioData) {

        if (!espApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).build();
        }

        String transcribed = aiService.transcribeAudio(audioData);
        if (transcribed.isBlank()) {
            transcribed = "[unintelligible]";
        }

        String responseText = aiService.generateResponse(sessionId, transcribed);
        byte[] audioResponse = aiService.synthesizeSpeech(responseText);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(audioResponse);
    }

    /**
     * Text-only chat endpoint — for testing the AI from the dashboard
     * or browser without needing the ESP32 hardware.
     * Protected by JWT (set in SecurityConfig).
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(
            @RequestParam String sessionId,
            @RequestBody Map<String, String> request) {

        String message = request.getOrDefault("message", "");
        String response = aiService.generateResponse(sessionId, message);
        return ResponseEntity.ok(Map.of("response", response, "sessionId", sessionId));
    }

    /** Clear a session's conversation history (call after alarm is resolved). */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        aiService.clearSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/sessions/count")
    public ResponseEntity<Map<String, Integer>> getSessionCount() {
        return ResponseEntity.ok(Map.of("activeSessions", aiService.getActiveSessionCount()));
    }
}
