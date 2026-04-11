#include <esp_now.h>
#include <WiFi.h>

// Message structure - must match the sender exactly
typedef struct struct_message {
  char nodeId[16];
  char eventType[24];
  int sensorValue;
} struct_message;

struct_message incomingData;

// Callback when data is received
void OnDataRecv(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
  if (len != sizeof(struct_message)) {
    Serial.print("Unexpected packet size: ");
    Serial.println(len);
    return;
  }

  memcpy(&incomingData, data, sizeof(incomingData));

  Serial.println("----- MESSAGE RECEIVED -----");
  Serial.print("From Node: ");
  Serial.println(incomingData.nodeId);

  Serial.print("Event Type: ");
  Serial.println(incomingData.eventType);

  Serial.print("Sensor Value: ");
  Serial.println(incomingData.sensorValue);
  Serial.println("----------------------------");
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  Serial.println("S3 Receiver Ready");
  Serial.print("S3 MAC Address: ");
  Serial.println(WiFi.macAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initializing ESP-NOW");
    return;
  }

  esp_now_register_recv_cb(OnDataRecv);
}

void loop() {
}