/**
 * =============================================================
 * VOXWALL Security System — Door Sensor Node
 * Hardware: ESP32-C3 Super Mini
 * 
 * Configuration:
 *   - Pin: 4 (INPUT_PULLUP, for MC-38 magnetic door sensor)
 *   - Trigger: LOW (door open/magnet separated)
 *   - Cooldown: 5 seconds (prevents rapid re-triggers)
 *   - Type: DOOR
 *   - Events: DOOR_OPENED, DOOR_CLOSED
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
#define SENSOR_TYPE       "DOOR"
#define SENSOR_PIN        4
#define SENSOR_INPUT_MODE INPUT_PULLUP

#define NODE_NAME         "Front Door Sensor"
#define NODE_LOCATION     "Entrance"

// S3 CENTRAL UNIT MAC ADDRESS (update this!)
uint8_t centralUnitMac[] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// ESP-NOW Channel (must match S3 central unit)
#define WIFI_CHANNEL 13

// Timing
#define DEBOUNCE_DELAY_MS     50UL
#define RETRIGGER_COOLDOWN_MS 5000UL
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
    Serial.printf("[DOOR] %s -> %s\n",
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

    Serial.println("\n========== DOOR SENSOR NODE ==========");
    Serial.println("VOXWALL Security System");
    Serial.printf("Node ID: %s\n", nodeId);
    Serial.printf("Type: %s | Name: %s | Location: %s\n", 
                  SENSOR_TYPE, NODE_NAME, NODE_LOCATION);
    Serial.printf("Pin: %d | Mode: INPUT_PULLUP | Trigger: LOW\n", SENSOR_PIN);
    Serial.printf("Cooldown: %lu ms | Channel: %d\n", 
                  RETRIGGER_COOLDOWN_MS, WIFI_CHANNEL);
    Serial.println("=======================================\n");

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
    Serial.println("[SYSTEM] Door sensor ready.\n");
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
                      (reading == LOW ? "CLOSED" : "OPEN"));
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

    // TRIGGER_LEVEL = LOW (door closed with magnet attached on INPUT_PULLUP)
    const int TRIGGER_LEVEL = LOW;

    if (reading != lastStableState) {
        lastStableState = reading;

        if (reading == TRIGGER_LEVEL) {
            // Magnet is present -> door is CLOSED
            sendEvent("DOOR_CLOSED", 0);
        } else {
            // Magnet is absent -> door is OPEN
            unsigned long now = millis();
            if (now - lastTriggerTime >= RETRIGGER_COOLDOWN_MS) {
                lastTriggerTime = now;
                sendEvent("DOOR_OPENED", 1);
            }
        }
    }

    delay(10);  // Prevent CPU hogging
}
