#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h> // Required for setting the Wi-Fi channel

#define PIR_PIN 2

// Message structure - must match receiver exactly
typedef struct struct_message {
  char nodeId[16];
  char eventType[24];
  int sensorValue;
} struct_message;

struct_message dataToSend;

// S3 MAC address (Ensure this matches your S3 exactly)
uint8_t receiverMac[] = {0x10, 0xB4, 0x1D, 0xE8, 0xE8, 0xC0};

int lastMotion = -1;

// CORRECTED Callback signature for your specific ESP32 Core version
void OnDataSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {
  Serial.print("Send Status: ");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "Success" : "Fail");
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(PIR_PIN, INPUT);

  // Set device as a Wi-Fi Station
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  
  // Explicitly match the Hub's Wi-Fi channel (Channel 1)
  esp_wifi_set_channel(1, WIFI_SECOND_CHAN_NONE);

  Serial.println("C3 PIR Sender Ready");

  if (esp_now_init() != ESP_OK) {
    Serial.println("ESP-NOW init failed");
    return;
  }

  esp_now_register_send_cb(OnDataSent);

  // Register peer
  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, receiverMac, 6);
  peerInfo.channel = 1; // Explicitly set channel 1 here too
  peerInfo.encrypt = false;

  if (esp_now_add_peer(&peerInfo) != ESP_OK) {
    Serial.println("Failed to add peer");
    return;
  }

  strcpy(dataToSend.nodeId, "NODE_1");
}

void loop() {
  int motion = digitalRead(PIR_PIN);

  if (motion != lastMotion) {
    lastMotion = motion;
    dataToSend.sensorValue = motion;

    if (motion == 1) {
      strcpy(dataToSend.eventType, "MOTION_DETECTED");
    } else {
      strcpy(dataToSend.eventType, "MOTION_IDLE");
    }

    Serial.println("----- SENDING MESSAGE -----");
    Serial.print("Node ID: ");
    Serial.println(dataToSend.nodeId);
    Serial.print("Event: ");
    Serial.println(dataToSend.eventType);

    esp_err_t result = esp_now_send(receiverMac, (uint8_t *)&dataToSend, sizeof(dataToSend));

    if (result != ESP_OK) {
      Serial.print("Send error code: ");
      Serial.println(result);
    }
    Serial.println("---------------------------");
  }

  // Fine for wired testing. Will need Deep Sleep for battery phase.
  delay(200); 
}