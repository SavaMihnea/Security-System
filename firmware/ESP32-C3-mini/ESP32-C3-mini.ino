// PIR SENSOR - NODE_1

#include <esp_now.h>
#include <WiFi.h>

#define PIR_PIN 2

// Message structure - must match receiver exactly
typedef struct struct_message {
  char nodeId[16];
  char eventType[24];
  int sensorValue;
} struct_message;

struct_message dataToSend;

// S3 MAC address
uint8_t receiverMac[] = {0x10, 0xB4, 0x1D, 0xE8, 0xE8, 0xC0};

int lastMotion = -1;

// Callback when data is sent
void OnDataSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {
  Serial.print("Send Status: ");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "Success" : "Fail");
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(PIR_PIN, INPUT);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  Serial.println("C3 PIR Sender Ready");

  if (esp_now_init() != ESP_OK) {
    Serial.println("ESP-NOW init failed");
    return;
  }

  esp_now_register_send_cb(OnDataSent);

  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, receiverMac, 6);
  peerInfo.channel = 0;
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

    Serial.print("Event Type: ");
    Serial.println(dataToSend.eventType);

    Serial.print("Sensor Value: ");
    Serial.println(dataToSend.sensorValue);

    esp_err_t result = esp_now_send(receiverMac, (uint8_t *)&dataToSend, sizeof(dataToSend));

    if (result == ESP_OK) {
      Serial.println("Send request queued");
    } else {
      Serial.print("Send error: ");
      Serial.println(result);
    }

    Serial.println("---------------------------");
  }

  delay(200);
}