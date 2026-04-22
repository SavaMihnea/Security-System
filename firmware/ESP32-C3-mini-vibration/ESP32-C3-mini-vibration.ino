#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>

// Using GPIO 4 for the 801S Digital Out (DO) pin
#define VIB_PIN 4 

// Message structure - must match receiver exactly
typedef struct struct_message {
  char nodeId[16];
  char eventType[24];
  int sensorValue;
} struct_message;

struct_message dataToSend;

// S3 Hub MAC address ---> PASTE YOUR S3 MAC HERE <---
uint8_t receiverMac[] = {0x10, 0xB4, 0x1D, 0xE8, 0xE8, 0xC0};

int lastVibState = -1;
unsigned long lastTriggerTime = 0;
const unsigned long cooldownTime = 2000; // 2 seconds cooldown after a shock

void OnDataSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {
  Serial.print("Send Status: ");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "Success" : "Fail");
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(VIB_PIN, INPUT);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_channel(1, WIFI_SECOND_CHAN_NONE);

  Serial.println("C3 Vibration Sensor (NODE 3) Ready");

  if (esp_now_init() != ESP_OK) {
    Serial.println("ESP-NOW init failed");
    return;
  }

  esp_now_register_send_cb(OnDataSent);

  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, receiverMac, 6);
  peerInfo.channel = 1; 
  peerInfo.encrypt = false;

  if (esp_now_add_peer(&peerInfo) != ESP_OK) {
    Serial.println("Failed to add peer");
    return;
  }

  // Assign this node its unique ID
  strcpy(dataToSend.nodeId, "NODE_3");
}

void loop() {
  int vibState = digitalRead(VIB_PIN);

  // Most 801S modules output LOW (0) when a vibration occurs, and HIGH (1) when idle.
  // We check if it changed AND if our 2-second cooldown has passed.
  if (vibState != lastVibState && (millis() - lastTriggerTime > cooldownTime)) {
    
    dataToSend.sensorValue = vibState;

    if (vibState == 0) { // Shock detected!
      strcpy(dataToSend.eventType, "VIBRATION_DETECTED");
      lastTriggerTime = millis(); // Start the cooldown timer
    } else {
      strcpy(dataToSend.eventType, "VIBRATION_IDLE");
    }

    Serial.println("----- SENDING MESSAGE -----");
    Serial.print("Node ID: ");
    Serial.println(dataToSend.nodeId);
    Serial.print("Event: ");
    Serial.println(dataToSend.eventType);

    esp_err_t result = esp_now_send(receiverMac, (uint8_t *)&dataToSend, sizeof(dataToSend));

    if (result != ESP_OK) {
      Serial.print("Send error: ");
      Serial.println(result);
    }
    Serial.println("---------------------------");
    
    lastVibState = vibState;
  }

  // Fast loop to ensure we don't miss the split-second vibration pulse
  delay(10); 
}