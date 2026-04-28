#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>

// Using GPIO 3 for the magnetic sensor (Wire 1 to GND, Wire 2 to GPIO 3)
#define DOOR_PIN 3 

// Message structure - must match receiver exactly
typedef struct struct_message {
  char nodeId[16];
  char eventType[24];
  int sensorValue;
} struct_message;

struct_message dataToSend;

// Example format: {0x10, 0xB4, 0x1D, 0xAA, 0xBB, 0xCC}
uint8_t receiverMac[] = {0x10, 0xB4, 0x1D, 0xE8, 0xE8, 0xC0}; 

int lastDoorState = -1;

// Callback signature for your specific ESP32 Core version
void OnDataSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {
  Serial.print("Send Status: ");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "Success" : "Fail");
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  // Enable internal pull-up resistor for the switch
  pinMode(DOOR_PIN, INPUT_PULLUP);

  // Set device as a Wi-Fi Station
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  
  // Explicitly match the Hub's Wi-Fi channel
  esp_wifi_set_channel(1, WIFI_SECOND_CHAN_NONE);

  Serial.println("C3 Magnetic Sensor (NODE 2) Ready");

  if (esp_now_init() != ESP_OK) {
    Serial.println("ESP-NOW init failed");
    return;
  }

  esp_now_register_send_cb(OnDataSent);

  // Register peer (S3 Hub)
  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, receiverMac, 6);
  peerInfo.channel = 1; 
  peerInfo.encrypt = false;

  if (esp_now_add_peer(&peerInfo) != ESP_OK) {
    Serial.println("Failed to add peer");
    return;
  }

  // Assign this node its unique ID
  strcpy(dataToSend.nodeId, "NODE_2");
}

void loop() {
  // Read the door state.
  // LOW (0) means magnet is close = door closed.
  // HIGH (1) means magnet is away = door open.
  int doorState = digitalRead(DOOR_PIN);

  if (doorState != lastDoorState) {
    lastDoorState = doorState;
    dataToSend.sensorValue = doorState;

    if (doorState == 1) {
      strcpy(dataToSend.eventType, "DOOR_OPEN");
    } else {
      strcpy(dataToSend.eventType, "DOOR_CLOSED");
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

  // Small delay for stability
  delay(100); 
}
