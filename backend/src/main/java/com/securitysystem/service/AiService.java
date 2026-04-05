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
 *   1. ESP32-S3 records intruder audio (I2S, 16kHz PCM/WAV)
 *   2. POST audio bytes here → Whisper STT → text
 *   3. text + conversation history → GPT-4o → response text
 *   4. response text → OpenAI TTS → MP3 bytes
 *   5. MP3 bytes returned to ESP32-S3 → played through MAX98357A + speaker
 *
 * Each intrusion event gets a unique sessionId to maintain separate
 * conversation histories. Sessions are in-memory (cleared on disarm or restart).
 */
@Service
@Slf4j
@SuppressWarnings("null")
public class AiService {

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    // sessionId → list of {role, content} messages for GPT-4o
    private final Map<String, List<Map<String, String>>> conversations = new ConcurrentHashMap<>();

    private static final String SYSTEM_PROMPT =
            "You are VOXWALL, an AI security system that has detected an intruder. " +
            "You speak in a calm, firm, and authoritative tone. " +
            "You have already announced the intrusion. Now you are engaged in active deterrence. " +
            "Remind the intruder they are being recorded, that law enforcement has been alerted, " +
            "and that leaving immediately is their only option. " +
            "If they speak, respond directly to what they say — stay in character as an unfeeling security AI. " +
            "Keep every response to 1-2 sentences maximum. " +
            "Never break character. Never show sympathy or help the intruder in any way.";

    /**
     * Pre-built deterrence phrases played immediately when an alarm triggers —
     * before the mic monitoring loop begins. These require no OpenAI call.
     * Add or edit freely; one is picked at random each alarm.
     */
    private static final List<String> DETERRENCE_PHRASES = List.of(
            "Perimeter breached. Activating top security measures. You have been identified.",
            "Intruder detected. This facility is under 24-hour AI surveillance. Law enforcement has been notified.",
            "Warning. You have triggered a security alert. Every second you remain here is being recorded.",
            "Alert. Motion detected. Facial recognition is active. Authorities are already on their way.",
            "Security breach detected. This is your only warning. Leave the premises immediately."
    );

    /**
     * Proactive statements the AI says on its own when the intruder is silent —
     * keeps the psychological pressure up between mic-detected responses.
     */
    private static final List<String> PROACTIVE_STATEMENTS = List.of(
            "It is not a good idea to rob this place. I will alert the police.",
            "You will go to jail. Every camera in this building has your face.",
            "I can hear you breathing. You cannot hide from this system.",
            "Your location is being transmitted to local authorities right now.",
            "The longer you stay, the worse this gets for you. Leave now.",
            "This property is fully monitored. There is no exit that is not covered."
    );

    private final Random random = new Random();

    public AiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

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

    /**
     * Sends the transcribed intruder speech to GPT-4o and returns the AI response text.
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
        requestBody.put("model", "gpt-4o");
        requestBody.put("messages", history);
        requestBody.put("max_tokens", 100);
        requestBody.put("temperature", 0.7);

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
            log.error("GPT-4o request failed: {}", e.getMessage());
            return "You have been detected. Do not move. Authorities are on their way.";
        }
    }

    /**
     * Converts the AI response text to MP3 audio bytes using OpenAI TTS.
     * The ESP32-S3 plays these bytes through the MAX98357A amplifier + speaker.
     */
    public byte[] synthesizeSpeech(String text) {
        if (!isApiKeyConfigured()) {
            return new byte[0];
        }

        Map<String, Object> requestBody = Map.of(
                "model", "tts-1",
                "input", text,
                "voice", "onyx"  // Deep, authoritative voice — best for deterrence
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

    /**
     * Called by the ESP32-S3 the moment an alarm triggers.
     * Picks a random deterrence phrase, converts to speech, and returns MP3 bytes.
     * This plays immediately — no mic recording needed yet.
     * Also seeds the conversation history so follow-up responses feel continuous.
     */
    public byte[] generateAlarmStartSpeech(String sessionId) {
        String phrase = DETERRENCE_PHRASES.get(random.nextInt(DETERRENCE_PHRASES.size()));
        log.info("[AI] Alarm start phrase for session {}: {}", sessionId, phrase);

        // Seed the conversation so GPT-4o knows what was already said
        conversations.put(sessionId, new ArrayList<>(List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "assistant", "content", phrase)
        )));

        return synthesizeSpeech(phrase);
    }

    /**
     * Called by the ESP32-S3 when the mic detects silence after a deterrence phrase.
     * Returns a proactive statement to keep psychological pressure up.
     * The statement is added to the conversation history so GPT-4o context stays coherent.
     */
    public byte[] generateProactiveStatement(String sessionId) {
        String statement = PROACTIVE_STATEMENTS.get(random.nextInt(PROACTIVE_STATEMENTS.size()));
        log.info("[AI] Proactive statement for session {}: {}", sessionId, statement);

        // Add to history so GPT knows what the system already said
        conversations.computeIfAbsent(sessionId, id -> new ArrayList<>(List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT)
        ))).add(Map.of("role", "assistant", "content", statement));

        return synthesizeSpeech(statement);
    }

    /** Clears conversation history for a session (call on alarm disarm). */
    public void clearSession(String sessionId) {
        conversations.remove(sessionId);
        log.info("AI conversation session cleared: {}", sessionId);
    }

    public int getActiveSessionCount() {
        return conversations.size();
    }

    private boolean isApiKeyConfigured() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }
}
