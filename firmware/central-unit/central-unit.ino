/**
 * =============================================================
 * VOXWALL Security System - Central Unit
 * Hardware: ESP32-S3 DevKit
 *
 * Responsibilities:
 *   - Receives sensor events from ESP32-C3 nodes via ESP-NOW
 *   - Forwards events to the Java backend over HTTPS
 *   - Controls siren via relay (GPIO)
 *   - Polls backend every 30 s to sync arm/disarm state
 *   - Runs AI deterrence conversation on alarm (Phase 6)
 *
 * Required libraries (Arduino Library Manager):
 *   - ArduinoJson      (Benoit Blanchon)
 *   - ESP32-audioI2S   (schreibfaul1)    <- for MP3 playback
 *
 * !! Before flashing: update WIFI_SSID, WIFI_PASSWORD,
 *    BACKEND_URL, and ESP_API_KEY below !!
 * =============================================================
 */

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <esp_now.h>
#include <SPIFFS.h>
#include <driver/i2s.h>
#include <ArduinoJson.h>
#include "Audio.h"          // ESP32-audioI2S library

// ---- User configuration -----------------------------------------
const char* WIFI_SSID     = "DIGI-2eS3";
const char* WIFI_PASSWORD = "6bZ93ey3tJ";
const char* BACKEND_URL   = "https://voxwall-security.com"; // No trailing slash
const char* ESP_API_KEY   = "pFTF3EJ3MEKx0NE5sO1tAGouPNNeYwJ5CsPAg2zVUXgjevad";
// -----------------------------------------------------------------

// ---- GPIO pins --------------------------------------------------
#define RELAY_SIREN_PIN   10    // Active-HIGH relay -> siren
#define RELAY_AUX_PIN     11    // Spare relay
#define STATUS_LED_PIN     2    // Status LED

// I2S - MAX98357A amplifier (speaker output, used by ESP32-audioI2S - I2S_NUM_0)
#define I2S_SPK_BCLK     12
#define I2S_SPK_LRC      13
#define I2S_SPK_DOUT     14

// I2S - INMP441 microphone (input, manual driver - I2S_NUM_1)
#define I2S_MIC_SCK       5
#define I2S_MIC_WS        4
#define I2S_MIC_SD        6
// -----------------------------------------------------------------

// ---- Timing constants -------------------------------------------
#define HEARTBEAT_INTERVAL_MS    30000UL  // Sync backend every 30 s
#define ENTRY_DELAY_MS           30000UL  // 30 s entry delay — matches backend AlarmManager + dashboard countdown
#define AI_SILENCE_TIMEOUT_MS     4000UL  // Proactive statement after 4 s silence
#define MIC_RECORD_DURATION_MS    5000UL  // Record 5 s per AI interaction
#define MIC_AMPLITUDE_THRESHOLD   20000   // Raw I2S peak above = "sound detected"
#define MIC_SAMPLE_RATE           16000   // Hz (Whisper requirement)
// -----------------------------------------------------------------

// ---- Arm mode enum (mirrors backend SystemConfig.ArmMode) -------
enum ArmMode {
    DISARMED,
    ARMED_HOME,         // No sensors processed (home, awake)
    ARMED_HOME_NIGHT,   // All sensors, NO entry delay (home, sleeping)
    ARMED_AWAY          // All sensors + 10-second entry delay on door events
};

// ---- Runtime state ----------------------------------------------
ArmMode       currentArmMode    = DISARMED;
bool          alarmActive       = false;
bool          entryDelayActive  = false;
unsigned long entryDelayStartMs = 0;
char          nodeId[30]        = "CENTRAL_S3";
unsigned long lastHeartbeatMs   = 0;
char          aiSessionId[40]   = "";
bool          audioPreloaded    = false;  // Set when /deterrence.mp3 pre-fetched during entry delay

// Non-blocking ESP-NOW event queue (processing heavy HTTP in loop, not in callback)
volatile bool pendingEvent  = false;
char          pendingNodeId[30];
char          pendingEventType[40];
// -----------------------------------------------------------------

// ---- ESP-NOW message struct (must match sensor-node.ino) --------
typedef struct __attribute__((packed)) SensorMessage {
    char nodeId[30];
    char eventType[40];
    int  sensorValue;
} SensorMessage;

// ---- Audio object (ESP32-audioI2S, uses I2S_NUM_0) --------------
Audio audio;

// =================================================================
// I2S microphone (INMP441, I2S_NUM_1)
// =================================================================
void initMic() {
    i2s_config_t cfg = {
        .mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
        .sample_rate          = MIC_SAMPLE_RATE,
        .bits_per_sample      = I2S_BITS_PER_SAMPLE_32BIT, // INMP441: 24-bit in 32-bit slot
        .channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT,
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count        = 8,
        .dma_buf_len          = 1024,
        .use_apll             = false
    };
    i2s_pin_config_t pins = {
        .bck_io_num   = I2S_MIC_SCK,
        .ws_io_num    = I2S_MIC_WS,
        .data_out_num = I2S_PIN_NO_CHANGE,
        .data_in_num  = I2S_MIC_SD
    };
    i2s_driver_install(I2S_NUM_1, &cfg, 0, NULL);
    i2s_set_pin(I2S_NUM_1, &pins);
}

// Sample 512 frames and return peak amplitude
int32_t measureMicAmplitude() {
    int32_t sample;
    size_t  bytesRead;
    int32_t peak = 0;
    for (int i = 0; i < 512; i++) {
        i2s_read(I2S_NUM_1, &sample, sizeof(sample), &bytesRead, portMAX_DELAY);
        sample >>= 8;   // 24-bit value in upper 24 bits of 32-bit slot
        if (abs(sample) > peak) peak = abs(sample);
    }
    return peak;
}

// Record MIC_RECORD_DURATION_MS of audio into SPIFFS as /recording.wav
// Returns total bytes written (0 on failure)
size_t recordToSpiffs() {
    File f = SPIFFS.open("/recording.wav", "w");
    if (!f) {
        Serial.println("[MIC] SPIFFS open failed");
        return 0;
    }

    // Reserve 44 bytes for WAV header; fill after recording
    uint8_t hdr[44] = {0};
    f.write(hdr, 44);

    const int TOTAL_SAMPLES = MIC_SAMPLE_RATE * (MIC_RECORD_DURATION_MS / 1000);
    int32_t rawSample;
    int16_t s16;
    size_t  bytesRead;
    size_t  totalPcm = 0;

    Serial.println("[MIC] Recording...");
    for (int i = 0; i < TOTAL_SAMPLES; i++) {
        i2s_read(I2S_NUM_1, &rawSample, sizeof(rawSample), &bytesRead, portMAX_DELAY);
        s16 = (int16_t)(rawSample >> 16);   // Upper 16 bits -> 16-bit PCM
        f.write((uint8_t*)&s16, 2);
        totalPcm += 2;
    }

    // Write WAV header
    uint32_t dataSize   = (uint32_t)totalPcm;
    uint32_t fileSize   = dataSize + 36;
    uint16_t audioFmt   = 1;
    uint16_t numChan    = 1;
    uint32_t sampleRate = MIC_SAMPLE_RATE;
    uint32_t byteRate   = MIC_SAMPLE_RATE * 2;
    uint16_t blockAlign = 2;
    uint16_t bitsPS     = 16;
    uint32_t subchunk1  = 16;

    f.seek(0);
    f.write((uint8_t*)"RIFF",    4);
    f.write((uint8_t*)&fileSize, 4);
    f.write((uint8_t*)"WAVE",    4);
    f.write((uint8_t*)"fmt ",    4);
    f.write((uint8_t*)&subchunk1, 4);
    f.write((uint8_t*)&audioFmt,  2);
    f.write((uint8_t*)&numChan,   2);
    f.write((uint8_t*)&sampleRate,4);
    f.write((uint8_t*)&byteRate,  4);
    f.write((uint8_t*)&blockAlign,2);
    f.write((uint8_t*)&bitsPS,    2);
    f.write((uint8_t*)"data",  4);
    f.write((uint8_t*)&dataSize,  4);
    f.close();

    Serial.printf("[MIC] Saved %u bytes to /recording.wav\n", totalPcm + 44);
    return totalPcm + 44;
}

// Play an MP3 file from SPIFFS (blocks until playback finishes)
void playMp3(const char* path) {
    audio.connecttoFS(SPIFFS, path);
    while (audio.isRunning()) {
        audio.loop();
    }
}

// =================================================================
// HTTP helpers
// =================================================================
WiFiClientSecure secureClient;

// POST jsonBody to url; stream MP3 response into SPIFFS destPath
bool postAndSaveMp3(const String& url, const String& jsonBody,
                    const char* sessionId, const char* destPath) {
    HTTPClient http;
    http.begin(secureClient, url);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-ESP-Key", ESP_API_KEY);
    if (sessionId && sessionId[0] != '\0') {
        http.addHeader("X-Session-Id", sessionId);
    }

    int code = http.POST(jsonBody);
    if (code != 200) {
        Serial.printf("[HTTP] POST %s -> %d\n", url.c_str(), code);
        http.end();
        return false;
    }

    File f = SPIFFS.open(destPath, "w");
    if (!f) { http.end(); return false; }

    WiFiClient* stream    = http.getStreamPtr();
    int         remaining = http.getSize();
    uint8_t     buf[512];

    while (http.connected() && (remaining > 0 || remaining == -1)) {
        int avail = stream->available();
        if (avail > 0) {
            int n = stream->readBytes(buf, min(avail, (int)sizeof(buf)));
            f.write(buf, n);
            if (remaining > 0) remaining -= n;
        }
        delay(1);
    }
    f.close();
    http.end();
    return true;
}

// POST WAV from SPIFFS to /api/ai/respond, save MP3 response
bool postWavAndSaveMp3(const char* sessionId) {
    File wavFile = SPIFFS.open("/recording.wav", "r");
    if (!wavFile) return false;

    size_t   wavSize = wavFile.size();
    uint8_t* wavBuf  = (uint8_t*)malloc(wavSize);
    if (!wavBuf) { wavFile.close(); return false; }
    wavFile.read(wavBuf, wavSize);
    wavFile.close();

    HTTPClient http;
    String url = String(BACKEND_URL) + "/api/ai/respond";
    http.begin(secureClient, url);
    http.addHeader("Content-Type", "audio/wav");
    http.addHeader("X-ESP-Key", ESP_API_KEY);
    http.addHeader("X-Session-Id", sessionId);

    int code = http.POST(wavBuf, wavSize);
    free(wavBuf);

    if (code != 200) {
        Serial.printf("[AI] /respond -> %d\n", code);
        http.end();
        return false;
    }

    File mp3 = SPIFFS.open("/response.mp3", "w");
    if (!mp3) { http.end(); return false; }

    WiFiClient* stream    = http.getStreamPtr();
    int         remaining = http.getSize();
    uint8_t     buf[512];

    while (http.connected() && (remaining > 0 || remaining == -1)) {
        int avail = stream->available();
        if (avail > 0) {
            int n = stream->readBytes(buf, min(avail, (int)sizeof(buf)));
            mp3.write(buf, n);
            if (remaining > 0) remaining -= n;
        }
        delay(1);
    }
    mp3.close();
    http.end();
    return true;
}

void sendEventToBackend(const char* fromNodeId, const char* eventType, const char* notes) {
    if (WiFi.status() != WL_CONNECTED) return;

    HTTPClient http;
    String url = String(BACKEND_URL) + "/api/esp/event";
    http.begin(secureClient, url);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-ESP-Key", ESP_API_KEY);

    StaticJsonDocument<256> doc;
    doc["nodeId"]    = fromNodeId;
    doc["eventType"] = eventType;
    doc["notes"]     = notes;
    String body;
    serializeJson(doc, body);

    int code = http.POST(body);
    if (code != 200) Serial.printf("[HTTP] event POST -> %d\n", code);
    http.end();
}

void parseStatusResponse(const String& json) {
    StaticJsonDocument<256> doc;
    if (deserializeJson(doc, json) != DeserializationError::Ok) return;

    const char* modeStr = doc["armMode"] | "DISARMED";
    ArmMode newMode = DISARMED;
    if      (strcmp(modeStr, "ARMED_HOME")       == 0) newMode = ARMED_HOME;
    else if (strcmp(modeStr, "ARMED_HOME_NIGHT") == 0) newMode = ARMED_HOME_NIGHT;
    else if (strcmp(modeStr, "ARMED_AWAY")       == 0) newMode = ARMED_AWAY;

    if (newMode != currentArmMode) {
        currentArmMode = newMode;
        Serial.printf("[SYNC] Arm mode -> %s\n", modeStr);
    }
    // Remote disarm cancels alarm and entry delay
    if (currentArmMode == DISARMED && (alarmActive || entryDelayActive)) {
        alarmActive      = false;
        entryDelayActive = false;
        audioPreloaded   = false;
        aiSessionId[0]   = '\0';
        digitalWrite(RELAY_SIREN_PIN, LOW);
        Serial.println("[SYNC] Alarm/delay cancelled by remote disarm");
    }
}

void sendHeartbeat() {
    if (WiFi.status() != WL_CONNECTED) return;

    HTTPClient http;
    String url = String(BACKEND_URL) + "/api/esp/heartbeat";
    http.begin(secureClient, url);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-ESP-Key", ESP_API_KEY);

    StaticJsonDocument<128> doc;
    doc["nodeId"] = nodeId;
    String body;
    serializeJson(doc, body);

    int code = http.POST(body);
    if (code == 200) parseStatusResponse(http.getString());
    http.end();
}

void registerWithBackend() {
    if (WiFi.status() != WL_CONNECTED) return;

    HTTPClient http;
    String url = String(BACKEND_URL) + "/api/esp/register";
    http.begin(secureClient, url);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-ESP-Key", ESP_API_KEY);

    StaticJsonDocument<256> doc;
    doc["nodeId"]   = nodeId;
    doc["name"]     = "Central Unit (ESP32-S3)";
    doc["location"] = "Front Door Panel";
    doc["type"]     = "CENTRAL";
    String body;
    serializeJson(doc, body);

    int code = http.POST(body);
    Serial.printf("[REGISTER] Backend: %d\n", code);
    http.end();
}

// =================================================================
// AI deterrence conversation
// =================================================================
void runAiConversation() {
    Serial.println("[AI] Starting conversation...");

    // Session ID may already be set when audio was pre-fetched during entry delay.
    // Generate a new one only on the immediate-alarm path (no entry delay).
    if (aiSessionId[0] == '\0') {
        snprintf(aiSessionId, sizeof(aiSessionId), "alarm_%lu", millis());
    }

    if (audioPreloaded) {
        // Audio was fetched during the entry delay countdown — play immediately.
        Serial.println("[AI] Playing pre-fetched deterrence audio");
        playMp3("/deterrence.mp3");
        audioPreloaded = false;
    } else {
        // No pre-fetch (ARMED_HOME_NIGHT or motion/vibration path) — fetch now.
        Serial.println("[AI] Fetching deterrence audio...");
        String url = String(BACKEND_URL) + "/api/ai/alarm-start";
        if (postAndSaveMp3(url, "{}", aiSessionId, "/deterrence.mp3")) {
            playMp3("/deterrence.mp3");
        }
    }

    // --- Mic monitoring loop ---
    // Continuously samples mic amplitude.
    //   Sound detected  → record 5 s WAV → Whisper + GPT + TTS → play response
    //   4 s of silence  → play a proactive deterrence statement
    //   Disarm via dashboard → sendHeartbeat() detects DISARMED → alarmActive = false → exit
    unsigned long lastSoundMs     = millis();
    unsigned long lastProactiveMs = millis();

    Serial.println("[AI] Mic monitoring loop started");

    while (alarmActive) {
        // Sync arm state with backend every HEARTBEAT_INTERVAL_MS
        if (millis() - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatMs = millis();
            sendHeartbeat();
            if (!alarmActive) break;
        }

        int32_t amplitude = measureMicAmplitude();
        Serial.printf("[MIC] Amplitude: %d\n", amplitude);

        if (amplitude > MIC_AMPLITUDE_THRESHOLD) {
            // Sound detected — record and respond
            lastSoundMs = millis();
            Serial.printf("[MIC] Sound detected (amp=%d) — recording 5 s\n", amplitude);

            if (recordToSpiffs() > 0 && postWavAndSaveMp3(aiSessionId)) {
                playMp3("/response.mp3");
            }
            lastProactiveMs = millis();   // Reset proactive timer after we responded

        } else if ((millis() - lastSoundMs     >= AI_SILENCE_TIMEOUT_MS) &&
                   (millis() - lastProactiveMs >= AI_SILENCE_TIMEOUT_MS)) {
            // Silence for AI_SILENCE_TIMEOUT_MS — say something unprompted
            Serial.println("[MIC] Silence — playing proactive deterrence");
            String url = String(BACKEND_URL) + "/api/ai/proactive";
            if (postAndSaveMp3(url, "{}", aiSessionId, "/proactive.mp3")) {
                playMp3("/proactive.mp3");
            }
            lastProactiveMs = millis();
        }

        delay(100);   // 100 ms between amplitude checks
    }
    // ---------------------------

    aiSessionId[0] = '\0';
    Serial.println("[AI] Conversation ended");
}

// =================================================================
// Alarm control
// =================================================================
void triggerAlarm(const char* sourceNodeId, const char* eventType) {
    if (alarmActive) return;

    alarmActive      = true;
    entryDelayActive = false;
    digitalWrite(RELAY_SIREN_PIN, HIGH);
    Serial.println("[ALARM] TRIGGERED");
    sendEventToBackend(sourceNodeId, "ALARM_TRIGGERED", eventType);

    runAiConversation();   // Blocks until alarmActive = false (remote disarm)

    // If we exit the conversation loop and alarm is still active (shouldn't happen
    // unless WiFi drops), leave siren on and let heartbeat eventually clear it
}

void stopAlarm() {
    alarmActive      = false;
    entryDelayActive = false;
    digitalWrite(RELAY_SIREN_PIN, LOW);
    Serial.println("[ALARM] Stopped");
    sendEventToBackend(nodeId, "ALARM_DISARMED", "Remote disarm");
}

// =================================================================
// Sensor event handler - applies arm mode logic and entry delay
// =================================================================
bool isDoorEvent(const char* eventType) {
    return strcmp(eventType, "DOOR_OPENED") == 0 ||
           strcmp(eventType, "DOOR_CLOSED")  == 0;
}

void registerOtherNode(const char* targetNodeId, const char* name, const char* location, const char* type) {
    if (WiFi.status() != WL_CONNECTED) return; //

    HTTPClient http;
    String url = String(BACKEND_URL) + "/api/esp/register"; //
    http.begin(secureClient, url); //
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-ESP-Key", ESP_API_KEY); //

    StaticJsonDocument<256> doc;
    doc["nodeId"]   = targetNodeId;
    doc["name"]     = name;
    doc["location"] = location;
    doc["type"]     = type; //

    String body;
    serializeJson(doc, body);

    int code = http.POST(body); //
    Serial.printf("[REG-OTHER] Node %s: %d\n", targetNodeId, code);
    http.end();
}

void handleSensorEvent(const char* fromNodeId, const char* eventType) {
    // --- NEW: AUTO-REGISTRATION LOGIC ---
    // If we hear a registration/online event from a node, tell the backend its name.
    if (strcmp(eventType, "NODE_ONLINE") == 0) {
        // This is where you set the Name and Location for the website
        registerOtherNode(fromNodeId, "Front Door Sensor", "Entrance", "DOOR");
    }
    // ------------------------------------

    // Always log the raw event for dashboard history
    sendEventToBackend(fromNodeId, eventType, ""); //

    switch (currentArmMode) { //

        case DISARMED:
        case ARMED_HOME:
            // Silent log only - no action (you're home)
            break;

        case ARMED_HOME_NIGHT:
            // Immediate alarm, no grace period (you're sleeping)
            if (!alarmActive) triggerAlarm(fromNodeId, eventType); //
            break;

        case ARMED_AWAY:
            if (alarmActive) break;  // Already alarmed

            if (isDoorEvent(eventType) && !entryDelayActive) {
                // Door opened: start 30-second entry delay countdown
                entryDelayActive  = true;
                entryDelayStartMs = millis();
                Serial.println("[ENTRY DELAY] 30 seconds to disarm...");
                sendEventToBackend(nodeId, "ALARM_TRIGGERED", "Entry delay started");

                // Pre-fetch TTS audio now so it plays the instant the countdown ends.
                // The HTTP call blocks ~3-5 s, which is fine — we have 30 s to spare.
                snprintf(aiSessionId, sizeof(aiSessionId), "alarm_%lu", millis());
                Serial.println("[AI] Pre-fetching alarm audio during entry delay...");
                String aiUrl = String(BACKEND_URL) + "/api/ai/alarm-start";
                audioPreloaded = postAndSaveMp3(aiUrl, "{}", aiSessionId, "/deterrence.mp3");
                if (audioPreloaded) Serial.println("[AI] Alarm audio ready.");
                else                Serial.println("[AI] Pre-fetch failed, will retry at alarm start.");

            } else if (!isDoorEvent(eventType)) {
                // Motion or vibration during AWAY: immediate alarm
                triggerAlarm(fromNodeId, eventType);
            }
            break;
    }
}

// =================================================================
// ESP-NOW callback - store event, process safely in loop()
// =================================================================
void onDataReceived(const uint8_t* mac_addr, const uint8_t* data, int len) {
    if (pendingEvent) return;   // Drop if we haven't processed the previous event yet

    SensorMessage msg;
    memcpy(&msg, data, sizeof(msg));

    strncpy(pendingNodeId,    msg.nodeId,    sizeof(pendingNodeId)    - 1);
    strncpy(pendingEventType, msg.eventType, sizeof(pendingEventType) - 1);
    pendingNodeId[sizeof(pendingNodeId) - 1]       = '\0';
    pendingEventType[sizeof(pendingEventType) - 1] = '\0';

    pendingEvent = true;

    Serial.printf("[ESP-NOW] Node: %s  Event: %s  Value: %d\n",
                  msg.nodeId, msg.eventType, msg.sensorValue);
}

// =================================================================
// Setup
// =================================================================
void setup() {
    Serial.begin(115200);
    delay(500);

    pinMode(RELAY_SIREN_PIN, OUTPUT);
    pinMode(RELAY_AUX_PIN,   OUTPUT);
    pinMode(STATUS_LED_PIN,  OUTPUT);
    digitalWrite(RELAY_SIREN_PIN, LOW);
    digitalWrite(RELAY_AUX_PIN,   LOW);

    // Init SPIFFS for WAV/MP3 temporary files
    if (!SPIFFS.begin(true)) {
        Serial.println("[SPIFFS] Mount failed!");
    } else {
        Serial.printf("[SPIFFS] Mounted (free: %u bytes)\n", SPIFFS.totalBytes() - SPIFFS.usedBytes());
    }

    // Connect to Wi-Fi (AP+STA required to use ESP-NOW + WiFi simultaneously)
    WiFi.mode(WIFI_AP_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    Serial.print("[WiFi] Connecting");
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.println("\n[WiFi] " + WiFi.localIP().toString());

    // Stable node ID from MAC address
    String mac = WiFi.macAddress();
    mac.replace(":", "");
    ("CENTRAL_" + mac).toCharArray(nodeId, sizeof(nodeId));
    Serial.printf("[ID] %s\n", nodeId);

    // HTTPS: skip cert verification in dev/demo
    // TODO: pin the Railway/Render cert before final deployment
    secureClient.setInsecure();

    // Init I2S microphone (INMP441, I2S_NUM_1)
    initMic();
    Serial.println("[I2S] Mic ready on I2S_NUM_1");

    // Init I2S speaker (MAX98357A, I2S_NUM_0 via ESP32-audioI2S)
    audio.setPinout(I2S_SPK_BCLK, I2S_SPK_LRC, I2S_SPK_DOUT);
    audio.setVolume(16);    // 0-21; scaled to keep output peaks ≤ 18000 raw (USB supply limit)
    Serial.println("[I2S] Speaker ready on I2S_NUM_0");

    // Init ESP-NOW
    if (esp_now_init() != ESP_OK) {
        Serial.println("[ESP-NOW] Init FAILED!");
        return;
    }
    esp_now_register_recv_cb(onDataReceived);
    Serial.println("[ESP-NOW] Ready");

    // Register and sync initial state
    registerWithBackend();
    sendHeartbeat();
    lastHeartbeatMs = millis();

    digitalWrite(STATUS_LED_PIN, HIGH);
    Serial.println("[VOXWALL] Central unit ready.");
}

// =================================================================
// Loop - non-blocking task runner
// =================================================================
void loop() {
    unsigned long now = millis();

    // Process any queued ESP-NOW event
    if (pendingEvent && !alarmActive) {
        pendingEvent = false;
        handleSensorEvent(pendingNodeId, pendingEventType);
    }

    // Entry delay countdown (ARMED_AWAY, door triggered)
    if (entryDelayActive && !alarmActive) {
        // Fast LED blink during countdown
        digitalWrite(STATUS_LED_PIN, (now / 200) % 2);

        if (now - entryDelayStartMs >= ENTRY_DELAY_MS) {
            Serial.println("[ENTRY DELAY] Expired - triggering alarm");
            entryDelayActive = false;
            triggerAlarm(nodeId, "DOOR_OPENED");
        }
        return;     // Skip heartbeat while counting down (will resume after)
    }

    // Periodic backend heartbeat
    if (now - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
        lastHeartbeatMs = now;
        sendHeartbeat();
    }

    // Status LED patterns
    if (alarmActive) {
        digitalWrite(STATUS_LED_PIN, (now / 250) % 2);   // Fast blink = alarm
    } else if (currentArmMode != DISARMED) {
        digitalWrite(STATUS_LED_PIN, HIGH);               // Solid = armed
    } else {
        digitalWrite(STATUS_LED_PIN, (now / 2000) % 2);  // Slow pulse = disarmed
    }

    delay(50);
}
