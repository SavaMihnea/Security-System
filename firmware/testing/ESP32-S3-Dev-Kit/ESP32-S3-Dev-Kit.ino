#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include <esp_mac.h> 

// --- SIREN RELAY SETTINGS ---
#define SIREN_PIN 10
bool isAlarmActive = false;
unsigned long alarmStartTime = 0;
const unsigned long alarmDuration = 5000; // Relay triggers for 5 seconds

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
  
  // Trigger the alarm if any critical event is detected
  if (strcmp(incomingData.eventType, "MOTION_DETECTED") == 0 || 
      strcmp(incomingData.eventType, "DOOR_OPEN") == 0 || 
      strcmp(incomingData.eventType, "VIBRATION_DETECTED") == 0) {
      
      // Only reset the timer if the alarm isn't already going off
      if (!isAlarmActive) {
        isAlarmActive = true;
        alarmStartTime = millis();
        Serial.println("THREAT DETECTED: RELAY ENGAGED!");
      }
  }
  Serial.println("----------------------------");
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  // Initialize Relay Pin (Active-Low setup)
  pinMode(SIREN_PIN, OUTPUT);
  digitalWrite(SIREN_PIN, HIGH); // HIGH keeps the relay OFF

  // 1. Turn on Wi-Fi interface
  WiFi.mode(WIFI_STA);
  
  // 2. Disable Wi-Fi Power Save
  esp_wifi_set_ps(WIFI_PS_NONE); 

  // 3. Read the hardware MAC
  uint8_t baseMac[6];
  esp_read_mac(baseMac, ESP_MAC_WIFI_STA);
  
  Serial.println("S3 Receiver Ready");
  Serial.print("S3 MAC Address: ");
  for (int i = 0; i < 5; i++) {
    if(baseMac[i] < 16) Serial.print("0"); 
    Serial.print(baseMac[i], HEX);
    Serial.print(":");
  }
  if(baseMac[5] < 16) Serial.print("0");
  Serial.println(baseMac[5], HEX);

  // 4. Force channel to 1
  WiFi.disconnect();
  esp_wifi_set_channel(1, WIFI_SECOND_CHAN_NONE);

  // 5. Initialize ESP-NOW
  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initializing ESP-NOW");
    return;
  }

  esp_now_register_recv_cb(OnDataRecv);
}

void loop() {
  // --- NON-BLOCKING ALARM LOGIC ---
  if (isAlarmActive) {
    if (millis() - alarmStartTime < alarmDuration) {
      // Alarm is active and within the 5-second window
      digitalWrite(SIREN_PIN, LOW); // LOW turns the relay ON
    } else {
      // 5 seconds have passed, turn it off
      digitalWrite(SIREN_PIN, HIGH); // HIGH turns the relay OFF
      isAlarmActive = false;
      Serial.println(" Relay Auto-Shutoff. Returning to standby.");
    }
  }
}