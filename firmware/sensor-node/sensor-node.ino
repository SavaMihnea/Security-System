/**
 * =============================================================
 * VOXWALL Security System — Sensor Node (LOGIC & PIN FIXED)
 * Hardware: ESP32-C3 Super Mini
 * =============================================================
 */

#include <Arduino.h>
#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>

// ---- NODE CONFIGURATION -----------------------------------
#define SENSOR_TYPE       "DOOR"
#define SENSOR_PIN        4             // Confirmed Pin 4
#define SENSOR_INPUT_MODE INPUT_PULLUP

#define NODE_NAME         "Front Door Sensor"
#define NODE_LOCATION     "Entrance"
// -----------------------------------------------------------

// S3 CENTRAL UNIT MAC ADDRESS
uint8_t centralUnitMac[] = {0x10, 0xB4, 0x1D, 0xE8, 0xE8, 0xC0};

// ROUTER WIFI CHANNEL
#define WIFI_CHANNEL 13

// ---- TIMING -----------------------------------------------
#define DEBOUNCE_DELAY_MS     50UL
#define RETRIGGER_COOLDOWN_MS 5000UL  
// -----------------------------------------------------------

typedef struct __attribute__((packed)) SensorMessage {
    char nodeId[30];
    char eventType[40];
    int  sensorValue;
} SensorMessage;

SensorMessage outMsg;
esp_now_peer_info_t peerInfo;
char nodeId[30] = "NODE_UNKNOWN";

// -------------------------------------------------------
void sendEvent(const char* eventType, int value) {
    strncpy(outMsg.nodeId,    nodeId,    sizeof(outMsg.nodeId) - 1);
    strncpy(outMsg.eventType, eventType, sizeof(outMsg.eventType) - 1);
    outMsg.nodeId[sizeof(outMsg.nodeId) - 1]       = '\0';
    outMsg.eventType[sizeof(outMsg.eventType) - 1] = '\0';
    outMsg.sensorValue = value;

    esp_err_t result = esp_now_send(centralUnitMac, (uint8_t*)&outMsg, sizeof(outMsg));
    Serial.printf("[ESP-NOW] %s (val:%d) -> %s\n",
                  eventType, value,
                  result == ESP_OK ? "SUCCESS" : "FAIL");
}

void onDataSent(const uint8_t* macAddr, esp_now_send_status_t status) {
    if (status != ESP_NOW_SEND_SUCCESS) {
        Serial.println("[!] Delivery Failed. Check S3 Power/Channel.");
    }
}

// -------------------------------------------------------
void setup() {
    Serial.begin(115200);
    
    // Brief wait for USB Serial to connect
    delay(1000);

    WiFi.mode(WIFI_STA);
    esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);

    String mac = WiFi.macAddress();
    mac.replace(":", "");
    ("NODE_" + mac).toCharArray(nodeId, sizeof(nodeId));

    Serial.println("\n--- VOXWALL SENSOR STARTING ---");
    Serial.println("[ID] " + String(nodeId));
    Serial.printf("[CONFIG] Pin: %d | Channel: %d\n", SENSOR_PIN, WIFI_CHANNEL);

    pinMode(SENSOR_PIN, SENSOR_INPUT_MODE);

    if (esp_now_init() != ESP_OK) {
        Serial.println("[!] ESP-NOW Init Failed");
        return;
    }
    esp_now_register_send_cb((esp_now_send_cb_t)onDataSent);

    memset(&peerInfo, 0, sizeof(peerInfo));
    memcpy(peerInfo.peer_addr, centralUnitMac, 6);
    peerInfo.channel = WIFI_CHANNEL; 
    peerInfo.encrypt = false;
    
    if (esp_now_add_peer(&peerInfo) != ESP_OK) {
        Serial.println("[!] Failed to add S3 Peer");
        return;
    }

    sendEvent("NODE_ONLINE", 1);
    Serial.println("[SYSTEM] Ready.");
}

// -------------------------------------------------------
int lastStableState      = -1; // Force initial read
int lastReadState        = LOW;
unsigned long lastDebounceTime  = 0;
unsigned long lastTriggerTime   = 0;

void loop() {
    int reading = digitalRead(SENSOR_PIN);
    
    // Heartbeat Debug Print (Every 2 seconds)
    static unsigned long lastDebugPrint = 0;
    if (millis() - lastDebugPrint > 2000) {
        Serial.printf("[DEBUG] Pin 4 Raw: %d (%s)\n", 
                      reading, (reading == LOW ? "MAGNET CLOSE" : "MAGNET APART"));
        lastDebugPrint = millis();
    }

    if (reading != lastReadState) {
        lastDebounceTime = millis();
    }
    lastReadState = reading;

    if ((millis() - lastDebounceTime) < DEBOUNCE_DELAY_MS) {
        return;
    }

    // TRIGGER_LEVEL is LOW (0) when magnet is together on INPUT_PULLUP
    const int TRIGGER_LEVEL = LOW; 

    if (reading != lastStableState) {
        lastStableState = reading;

        if (reading == TRIGGER_LEVEL) {
            // MAGNET IS TOGETHER -> DOOR IS CLOSED
            sendEvent("DOOR_CLOSED", 0);
        } else {
            // MAGNET IS APART -> DOOR IS OPENED
            unsigned long now = millis();
            if (now - lastTriggerTime >= RETRIGGER_COOLDOWN_MS) {
                lastTriggerTime = now;
                sendEvent("DOOR_OPENED", 1);
            }
        }
    }
}