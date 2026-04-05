/**
 * =============================================================
 * SENTINEL Security System — Sensor Node
 * Hardware: ESP32-C3 Super Mini
 *
 * Responsibilities:
 *   - Reads one or more sensors (PIR / vibration / magnetic door)
 *   - Sends events to the central ESP32-S3 unit via ESP-NOW
 *   - Debounces sensor input to prevent false triggers
 *
 * !! Before flashing each node:
 *   1. Set CENTRAL_UNIT_MAC to the MAC address of the ESP32-S3
 *      (print WiFi.macAddress() in the central unit's setup())
 *   2. Set SENSOR_TYPE to match the attached hardware
 *   3. Set SENSOR_PIN to the GPIO the sensor is wired to
 *   4. Set NODE_NAME and NODE_LOCATION for the dashboard
 * =============================================================
 */

#include <Arduino.h>
#include <esp_now.h>
#include <WiFi.h>

// ---- NODE CONFIGURATION — change per node -----------------
// Sensor type: MOTION | VIBRATION | DOOR
#define SENSOR_TYPE  "MOTION"

// GPIO connected to the sensor output
#define SENSOR_PIN   4

// Use INPUT_PULLUP for DOOR (MC-38) and VIBRATION (801S) sensors.
// These are normally-open: floating HIGH when idle, pulled LOW when triggered.
// Use INPUT for MOTION (HC-SR501): active-HIGH push-pull output.
// INPUT   → triggered when pin reads HIGH
// INPUT_PULLUP → triggered when pin reads LOW (logic inverted)
#define SENSOR_INPUT_MODE  INPUT   // Change to INPUT_PULLUP for DOOR / VIBRATION

// Human-readable name (shown in dashboard)
#define NODE_NAME      "Front Door Motion"
#define NODE_LOCATION  "Front Entrance"
// -----------------------------------------------------------

// MAC address of the ESP32-S3 central unit
// Example: {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF}
uint8_t centralUnitMac[] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// ---- TIMING -----------------------------------------------
#define DEBOUNCE_DELAY_MS    50UL
#define RETRIGGER_COOLDOWN_MS 5000UL  // Ignore re-triggers within 5 seconds
// -----------------------------------------------------------

// ---- ESP-NOW message structure ----------------------------
// Must match the struct in central-unit.ino exactly
typedef struct __attribute__((packed)) SensorMessage {
    char nodeId[30];
    char eventType[40];
    int  sensorValue;
} SensorMessage;

SensorMessage outMsg;
esp_now_peer_info_t peerInfo;
char nodeId[30] = "NODE_UNKNOWN";

// -------------------------------------------------------
// Send an event message to the central unit via ESP-NOW
// -------------------------------------------------------
void sendEvent(const char* eventType, int value) {
    strncpy(outMsg.nodeId,    nodeId,    sizeof(outMsg.nodeId) - 1);
    strncpy(outMsg.eventType, eventType, sizeof(outMsg.eventType) - 1);
    // strncpy does not null-terminate when src >= dest size — guarantee it explicitly
    outMsg.nodeId[sizeof(outMsg.nodeId) - 1]       = '\0';
    outMsg.eventType[sizeof(outMsg.eventType) - 1] = '\0';
    outMsg.sensorValue = value;

    esp_err_t result = esp_now_send(centralUnitMac, (uint8_t*)&outMsg, sizeof(outMsg));
    Serial.printf("[ESP-NOW] Sent %s (value=%d)  result=%s\n",
                  eventType, value,
                  result == ESP_OK ? "OK" : "FAIL");
}

void onDataSent(const uint8_t* macAddr, esp_now_send_status_t status) {
    if (status != ESP_NOW_SEND_SUCCESS) {
        Serial.println("[ESP-NOW] Delivery FAILED — is central unit powered on?");
    }
}

// -------------------------------------------------------
// Setup
// -------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(500);

    // Build stable node ID from MAC address
    WiFi.mode(WIFI_STA);
    String mac = WiFi.macAddress();
    mac.replace(":", "");
    ("NODE_" + mac).toCharArray(nodeId, sizeof(nodeId));
    Serial.println("[ID] Node ID: " + String(nodeId));
    Serial.printf("[CONFIG] Type: %s  Pin: %d  Name: %s  Mode: %s\n",
                  SENSOR_TYPE, SENSOR_PIN, NODE_NAME,
                  (SENSOR_INPUT_MODE == INPUT_PULLUP) ? "INPUT_PULLUP" : "INPUT");

    // Configure sensor GPIO
    pinMode(SENSOR_PIN, SENSOR_INPUT_MODE);

    // Initialize ESP-NOW
    if (esp_now_init() != ESP_OK) {
        Serial.println("[ESP-NOW] Init FAILED!");
        return;
    }
    esp_now_register_send_cb(onDataSent);

    // Register central unit as ESP-NOW peer
    // Zero all fields first — uninitialized lmk/ifidx fields cause erratic behaviour
    memset(&peerInfo, 0, sizeof(peerInfo));
    memcpy(peerInfo.peer_addr, centralUnitMac, 6);
    peerInfo.channel = 0;
    peerInfo.encrypt = false;
    if (esp_now_add_peer(&peerInfo) != ESP_OK) {
        Serial.println("[ESP-NOW] Failed to add central unit peer!");
        return;
    }

    // Announce presence to central unit
    sendEvent("NODE_ONLINE", 1);
    Serial.println("[SENTINEL] Sensor node ready.");
}

// -------------------------------------------------------
// Loop — sensor reading with debounce
// -------------------------------------------------------
int  lastStableState      = LOW;
int  lastReadState        = LOW;
unsigned long lastDebounceTime  = 0;
unsigned long lastTriggerTime   = 0;

void loop() {
    int reading = digitalRead(SENSOR_PIN);

    // Debounce
    if (reading != lastReadState) {
        lastDebounceTime = millis();
    }
    lastReadState = reading;

    if ((millis() - lastDebounceTime) < DEBOUNCE_DELAY_MS) {
        delay(10);
        return;
    }

    // Normalize: TRIGGER_LEVEL is LOW for INPUT_PULLUP sensors, HIGH for INPUT sensors
    // This lets the rest of the logic stay the same regardless of sensor wiring
    const int TRIGGER_LEVEL = (SENSOR_INPUT_MODE == INPUT_PULLUP) ? LOW : HIGH;

    // State has been stable for DEBOUNCE_DELAY_MS
    if (reading != lastStableState) {
        lastStableState = reading;

        if (reading == TRIGGER_LEVEL) {
            // Active edge — sensor triggered
            unsigned long now = millis();
            if (now - lastTriggerTime >= RETRIGGER_COOLDOWN_MS) {
                lastTriggerTime = now;

                if (strcmp(SENSOR_TYPE, "MOTION") == 0) {
                    sendEvent("MOTION_DETECTED", 1);
                } else if (strcmp(SENSOR_TYPE, "VIBRATION") == 0) {
                    sendEvent("VIBRATION_DETECTED", 1);
                } else if (strcmp(SENSOR_TYPE, "DOOR") == 0) {
                    sendEvent("DOOR_OPENED", 1);
                }
            }
        } else {
            // Inactive edge (only relevant for door sensor)
            if (strcmp(SENSOR_TYPE, "DOOR") == 0) {
                sendEvent("DOOR_CLOSED", 0);
            }
        }
    }

    delay(10);
}
