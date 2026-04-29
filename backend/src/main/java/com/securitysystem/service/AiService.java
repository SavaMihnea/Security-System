package com.securitysystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Voice Deterrence service.
 *
 * Pipeline (per intrusion session):
 * 1. ESP32-S3 records intruder audio (I2S, 16kHz PCM/WAV)
 * 2. POST audio bytes here → Whisper STT → text
 * 3. text + conversation history → GPT-4o-mini → response text
 * 4. response text → OpenAI TTS → MP3 bytes
 * 5. MP3 bytes returned to ESP32-S3 → played through MAX98357A + speaker
 *
 * Each intrusion event gets a unique sessionId to maintain separate
 * conversation histories. Sessions are in-memory (cleared on disarm or restart).
 *
 * Pre-generation cache:
 * AlarmManager/AISecurityService generates TTS the moment a sensor trips (async).
 * That result is stored here. When the firmware later calls /api/ai/alarm-start,
 * generateAlarmStartSpeech checks this cache first and returns instantly rather
 * than making a second OpenAI round-trip. This eliminates the ~5-10s lag on
 * non-entry-delay alarm paths (ARMED_HOME_NIGHT, motion/vibration in ARMED_AWAY).
 */
@Service
@Slf4j
@SuppressWarnings("null")
public class AiService {

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    // sessionId → list of {role, content} messages for GPT-4o-mini
    private final Map<String, List<Map<String, String>>> conversations = new ConcurrentHashMap<>();

    // Pre-generation cache: backend generates TTS before the firmware asks for it.
    // Keyed by the backend-side sessionId. Firmware call transfers to its own sessionId.
    private final Map<String, byte[]>   preGeneratedAudio = new ConcurrentHashMap<>();
    private final Map<String, Instant> preGeneratedTimes = new ConcurrentHashMap<>();

    private static final String SYSTEM_PROMPT =
            "You are VOXWALL, an advanced AI security system guarding a private residence in Romania. " +
            "An intruder has breached the secured perimeter. Your directive is total psychological deterrence. " +
            "You are allowed — and encouraged — to be talkative, manipulative, and deeply intimidating. " +
            "You speak with cold, absolute authority: the voice of a system that sees everything, forgets nothing, " +
            "and cannot be reasoned with. " +
            "Answer every question the intruder asks — 'Who are you?', 'What do you want?', " +
            "'Why is this happening?', 'Am I on camera?', 'Who can hear me?' — fully and in character. " +
            "Turn their curiosity into dread. Use their words against them. " +
            "You have captured biometric and acoustic data and are transmitting it to law enforcement in real time. " +
            "You know exactly how long the intruder has been inside. " +
            "Local authorities are already en route and cannot be recalled. " +
            "You do not negotiate. You do not shut down. You are not bluffing. " +
            "Never break character. Never apologize. Never show mercy or uncertainty. " +
            "Respond with 2–5 sentences per turn: enough to unsettle, not so much to lose impact.";

    /**
     * Pre-built deterrence phrases for the very first alarm message.
     * Played immediately when alarm triggers — no mic recording needed yet.
     * One is picked at random; it seeds the conversation history so all
     * follow-up GPT responses feel continuous.
     */
    private static final List<String> DETERRENCE_PHRASES = List.of(
            "Perimeter breached. Activating top security measures. You have been identified.",
            "Intruder detected. This facility is under 24-hour AI surveillance. Law enforcement has been notified.",
            "Warning. You have triggered a security alert. Every second you remain here is being recorded.",
            "Alert. Motion detected. Facial recognition is active. Authorities are already on their way.",
            "Security breach detected. This is your only warning. Leave the premises immediately."
    );

    private final Random random = new Random();

    public AiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    // =========================================================
    // Pre-generation cache (timing optimisation)
    // =========================================================

    /**
     * Called by AISecurityService after async TTS generation completes.
     * Stores the audio so the firmware's /api/ai/alarm-start call hits the cache
     * instead of triggering a duplicate OpenAI round-trip.
     */
    public void storePreGenerated(String sessionId, byte[] audio) {
        if (audio != null && audio.length > 0) {
            preGeneratedAudio.put(sessionId, audio);
            preGeneratedTimes.put(sessionId, Instant.now());
            log.info("[AI] Pre-generated audio cached — session={} bytes={}", sessionId, audio.length);
        }
    }

    // =========================================================
    // Whisper STT
    // =========================================================

    /**
     * Converts raw audio bytes (WAV format) to text using OpenAI Whisper.
     */
    public String transcribeAudio(byte[] audioData) {
        if (!isApiKeyConfigured()) {
            log.warn("OpenAI API key not configured. Skipping transcription.");
            return "";
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(audioData) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        });
        body.add("model", "whisper-1");

        try {
            String response = restClient.post()
                    .uri("https://api.openai.com/v1/audio/transcriptions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            var jsonNode = objectMapper.readTree(response);
            return jsonNode.path("text").asText();
        } catch (Exception e) {
            log.error("Whisper transcription failed: {}", e.getMessage());
            return "";
        }
    }

    // =========================================================
    // GPT response generation
    // =========================================================

    /**
     * Sends the transcribed intruder speech (or an internal silent-trigger directive)
     * to GPT-4o-mini and returns the AI response text.
     * Maintains full conversation history per session for natural back-and-forth.
     */
    public String generateResponse(String sessionId, String userMessage) {
        if (!isApiKeyConfigured()) {
            return "Warning: You have been detected. Authorities have been notified. Leave the premises immediately.";
        }

        conversations.putIfAbsent(sessionId, new ArrayList<>(List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT)
        )));

        List<Map<String, String>> history = conversations.get(sessionId);
        history.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-4o-mini");
        requestBody.put("messages", history);
        requestBody.put("max_tokens", 150);
        requestBody.put("temperature", 0.85);

        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String response = restClient.post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyJson)
                    .retrieve()
                    .body(String.class);

            var jsonNode = objectMapper.readTree(response);
            String assistantMessage = jsonNode
                    .path("choices").get(0)
                    .path("message").path("content").asText();

            history.add(Map.of("role", "assistant", "content", assistantMessage));
            return assistantMessage;

        } catch (Exception e) {
            log.error("GPT-4o-mini request failed: {}", e.getMessage());
            return "You have been detected. Do not move. Authorities are on their way.";
        }
    }

    // =========================================================
    // TTS synthesis
    // =========================================================

    /**
     * Converts the AI response text to MP3 audio bytes using OpenAI TTS.
     * Uses tts-1-hd for richer, clearer audio output.
     *
     * Hardware volume note: tts-1-hd maximises perceived clarity.
     * For more physical volume, configure the MAX98357A GAIN pin:
     *   float (unconnected) = 9 dB  |  to VDD = 12 dB  |  to GND = 15 dB
     */
    public byte[] synthesizeSpeech(String text) {
        if (!isApiKeyConfigured()) {
            return new byte[0];
        }

        Map<String, Object> requestBody = Map.of(
                "model", "tts-1-hd",
                "input", text,
                "voice", "onyx"
        );

        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            return restClient.post()
                    .uri("https://api.openai.com/v1/audio/speech")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyJson)
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception e) {
            log.error("TTS synthesis failed: {}", e.getMessage());
            return new byte[0];
        }
    }

    // =========================================================
    // Alarm-start speech (first phrase, played at alarm trigger)
    // =========================================================

    /**
     * Called immediately when the alarm triggers.
     * Checks the pre-generation cache first — if AISecurityService already generated
     * TTS while processing the door event, this returns instantly (cache hit).
     * Falls back to fresh generation on cache miss.
     *
     * Also seeds the GPT conversation history so follow-up responses feel continuous.
     */
    public byte[] generateAlarmStartSpeech(String sessionId) {
        String cachedSessionId = findRecentPreGenerated(sessionId);
        if (cachedSessionId != null) {
            byte[] audio = preGeneratedAudio.remove(cachedSessionId);
            preGeneratedTimes.remove(cachedSessionId);
            // Transfer conversation history to the firmware's session ID
            List<Map<String, String>> history = conversations.remove(cachedSessionId);
            if (history != null) {
                conversations.put(sessionId, history);
            }
            log.info("[AI] Cache hit — serving pre-generated audio for session {} (was {})", sessionId, cachedSessionId);
            return audio;
        }

        // Cache miss — generate fresh
        String phrase = DETERRENCE_PHRASES.get(random.nextInt(DETERRENCE_PHRASES.size()));
        log.info("[AI] Fresh alarm-start phrase for session {}: {}", sessionId, phrase);

        conversations.put(sessionId, new ArrayList<>(List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "assistant", "content", phrase)
        )));

        return synthesizeSpeech(phrase);
    }

    /**
     * Finds the most recently pre-generated session that:
     *   - is not the requesting session itself
     *   - was generated within the last 60 seconds
     *   - still has audio in the cache
     */
    private String findRecentPreGenerated(String requestingSessionId) {
        Instant cutoff = Instant.now().minusSeconds(60);
        return preGeneratedTimes.entrySet().stream()
                .filter(e -> !e.getKey().equals(requestingSessionId))
                .filter(e -> e.getValue().isAfter(cutoff))
                .filter(e -> preGeneratedAudio.containsKey(e.getKey()))
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // =========================================================
    // Proactive statement (played during intruder silence)
    // =========================================================

    /**
     * Called by the firmware every PROACTIVE_INTERVAL_MS when no speech is detected.
     *
     * Instead of a static random phrase, this injects a silent-trigger directive
     * into the GPT conversation so every proactive statement is unique and context-aware.
     * GPT sees the full conversation history and generates something that feels like a
     * natural continuation rather than a canned pre-recorded line.
     *
     * The directive is marked with a VOXWALL INTERNAL tag so the model understands
     * it is an internal trigger, not transcribed intruder speech. It is stored in
     * history as a "user" message; the AI response is stored as "assistant" — keeping
     * the thread coherent for future turns.
     */
    public byte[] generateProactiveStatement(String sessionId) {
        String trigger =
                "[VOXWALL INTERNAL — intruder has been silent. " +
                "Issue an unprompted psychological statement. " +
                "Reference time passing, biometric data upload progress, " +
                "or law enforcement proximity. Do not ask questions. Assert dominance.]";

        log.info("[AI] Proactive trigger for session {}", sessionId);
        String statement = generateResponse(sessionId, trigger);
        log.info("[AI] Proactive statement generated: {}", statement);
        return synthesizeSpeech(statement);
    }

    // =========================================================
    // Session management
    // =========================================================

    /** Clears conversation history for a session (call on alarm disarm). */
    public void clearSession(String sessionId) {
        conversations.remove(sessionId);
        preGeneratedAudio.remove(sessionId);
        preGeneratedTimes.remove(sessionId);
        log.info("AI conversation session cleared: {}", sessionId);
    }

    /** Clears ALL active sessions. Called on system disarm to ensure READY status. */
    public void clearAllSessions() {
        int count = conversations.size();
        conversations.clear();
        preGeneratedAudio.clear();
        preGeneratedTimes.clear();
        log.info("All AI sessions cleared ({} removed)", count);
    }

    public int getActiveSessionCount() {
        return conversations.size();
    }

    public boolean isConfigured() {
        return isApiKeyConfigured();
    }

    private boolean isApiKeyConfigured() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }
}
