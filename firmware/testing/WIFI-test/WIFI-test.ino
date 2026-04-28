#include <WiFi.h>

// --- FILL IN YOUR WI-FI ---
const char* ssid = "DIGI-2eS3"; 
const char* password = "6bZ93ey3tJ";

void setup() {
  Serial.begin(115200);
  delay(1000);
  
  Serial.println("\n🔍 Starting Wi-Fi Probe...");
  
  // Set to Station mode (connecting to a router)
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  
  Serial.println("\n✅ Wi-Fi Connected Successfully!");
  
  // --- THE TWO PIECES OF GOLD WE NEED ---
  Serial.println("=====================================");
  
  Serial.print("📡 ROUTER WIFI CHANNEL: ");
  Serial.println(WiFi.channel());
  
  Serial.print("🏷️ S3 MAC ADDRESS: ");
  Serial.println(WiFi.macAddress());
  
  Serial.println("=====================================");
  Serial.println("Write these down, then you can flash the C3 nodes!");
}

void loop() {
  // We don't need to do anything else.
}
