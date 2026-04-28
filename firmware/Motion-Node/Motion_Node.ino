/**
 * =============================================================
 * VOXWALL Security System — Motion Sensor Node
 * Hardware: ESP32-C3 Super Mini
 * 
 * Configuration:
 *   - Pin: 4 (INPUT, for HC-SR501 PIR motion sensor)
 *   - Trigger: HIGH (motion detected)
 *   - Cooldown: 30 seconds (prevents rapid re-triggers on continuous motion)
 *   - Type: MOTION
 *   - Events: MOTION_DETECTED
 * 
 * BEFORE FLASHING:
 *   1. Update centralUnitMac[] with your ESP32-S3 gateway MAC
 *   2. Confirm SENSOR_PIN, NODE_NAME, NODE_LOCATION
 *   3. Enable "USB CDC On Boot" in Arduino IDE
 * =============================================================
 */

#include <Arduino.h>
#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>

// ---- CONFIGURATION ----------------------------------------
#define SENSOR_TYPE       "MOTION"
#define SENSOR_PIN        4
#define SENSOR_INPUT_MODE INPUT  // PIR outputs push-pull HIGH on motion

#define NODE_NAME         "Front Door Motion"
#define NODE_LOCATION     "Entrance"

// S3 CENTRAL UNIT MAC ADDRESS (update this!)
uint8_t centralUnitMac[] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// ESP-NOW Channel (must match S3 central unit)
#define WIFI_CHANNEL 13

// Timing
#define DEBOUNCE_DELAY_MS     50UL
#define RETRIGGER_COOLDOWN_MS 30000UL  // 30 seconds for PIR
// ---------------------------------------------------------

// ESP-NOW Message Structure
typedef struct __attribute__((packed)) SensorMessage {
    char nodeId[30];
    char eventType[40];
    int  sensorValue;
} SensorMessage;

SensorMessage outMsg;
esp_now_peer_info_t peerInfo;
char nodeId[30] = "NODE_UNKNOWN";

// ---------------------------------------------------------
// Send event via ESP-NOW to central unit
// ---------------------------------------------------------
void sendEvent(const char* eventType, int value) {
    strncpy(outMsg.nodeId,    nodeId,    sizeof(outMsg.nodeId) - 1);
    strncpy(outMsg.eventType, eventType, sizeof(outMsg.eventType) - 1);
    outMsg.nodeId[sizeof(outMsg.nodeId) - 1]       = '\0';
    outMsg.eventType[sizeof(outMsg.eventType) - 1] = '\0';
    outMsg.sensorValue = value;

    esp_err_t result = esp_now_send(centralUnitMac, (uint8_t*)&outMsg, sizeof(outMsg));
    Serial.printf("[MOTION] %s -> %s\n",
                  eventType,
                  result == ESP_OK ? "Sent" : "FAIL");
}

void onDataSent(const uint8_t* macAddr, esp_now_send_status_t status) {
    if (status != ESP_NOW_SEND_SUCCESS) {
        Serial.println("[!] ESP-NOW delivery failed. Check S3 gateway.");
    }
}

// ---------------------------------------------------------
// Setup
// ---------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(1000);

    // Configure WiFi for ESP-NOW
    WiFi.mode(WIFI_STA);
    esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);

    // Generate unique node ID from MAC
    String mac = WiFi.macAddress();
    mac.replace(":", "");
    ("NODE_" + mac).toCharArray(nodeId, sizeof(nodeId));

    Serial.println("\n========== MOTION SENSOR NODE ==========");
    Serial.println("VOXWALL Security System");
    Serial.printf("Node ID: %s\n", nodeId);
    Serial.printf("Type: %s | Name: %s | Location: %s\n",
                  SENSOR_TYPE, NODE_NAME, NODE_LOCATION);
    Serial.printf("Pin: %d | Mode: INPUT | Trigger: HIGH\n", SENSOR_PIN);
    Serial.printf("Cooldown: %lu ms | Channel: %d\n",
                  RETRIGGER_COOLDOWN_MS, WIFI_CHANNEL);
    Serial.println("========================================\n");

    // Initialize sensor pin
    pinMode(SENSOR_PIN, SENSOR_INPUT_MODE);

    // Initialize ESP-NOW
    if (esp_now_init() != ESP_OK) {
        Serial.println("[!] ESP-NOW initialization failed!");
        return;
    }

    esp_now_register_send_cb((esp_now_send_cb_t)onDataSent);

    // Register central unit as peer
    memset(&peerInfo, 0, sizeof(peerInfo));
    memcpy(peerInfo.peer_addr, centralUnitMac, 6);
    peerInfo.channel = WIFI_CHANNEL;
    peerInfo.encrypt = false;

    if (esp_now_add_peer(&peerInfo) != ESP_OK) {
        Serial.println("[!] Failed to register central unit!");
        return;
    }

    // Notify central unit that this node is online
    sendEvent("NODE_ONLINE", 1);
    Serial.println("[SYSTEM] Motion sensor ready.\n");
}

// ---------------------------------------------------------
// Main Loop
// ---------------------------------------------------------
int lastStableState = -1;
int lastReadState = LOW;
unsigned long lastDebounceTime = 0;
unsigned long lastTriggerTime = 0;

void loop() {
    int reading = digitalRead(SENSOR_PIN);

    // Debug output every 2 seconds
    static unsigned long lastDebugPrint = 0;
    if (millis() - lastDebugPrint > 2000) {
        Serial.printf("[DEBUG] Pin 4: %d (%s)\n",
                      reading,
                      (reading == HIGH ? "MOTION DETECTED" : "IDLE"));
        lastDebugPrint = millis();
    }

    // Debounce: if pin changed, start timer
    if (reading != lastReadState) {
        lastDebounceTime = millis();
    }
    lastReadState = reading;

    // Check if debounce window has passed
    if ((millis() - lastDebounceTime) < DEBOUNCE_DELAY_MS) {
        return;
    }

    // TRIGGER_LEVEL = HIGH (PIR detected motion)
    const int TRIGGER_LEVEL = HIGH;

    if (reading != lastStableState) {
        lastStableState = reading;

        if (reading == TRIGGER_LEVEL) {
            // Motion detected
            unsigned long now = millis();
            if (now - lastTriggerTime >= RETRIGGER_COOLDOWN_MS) {
                lastTriggerTime = now;
                sendEvent("MOTION_DETECTED", 1);
            }
        }
        // Note: We don't send "MOTION_CLEARED" when PIR goes LOW
        // The 30s cooldown prevents re-triggers on continuous motion
    }

    delay(10);  // Prevent CPU hogging
}
