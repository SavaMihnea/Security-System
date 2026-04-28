#include <driver/i2s.h>

// Pins must match central-unit.ino exactly
#define I2S_MIC_SCK  5   // INMP441 SCK  → GPIO 5
#define I2S_MIC_WS   4   // INMP441 WS   → GPIO 4
#define I2S_MIC_SD   6   // INMP441 SD   → GPIO 6

// Must use I2S_NUM_1 — same port as central-unit.ino.
// I2S_NUM_0 is reserved for the MAX98357A speaker.
#define MIC_I2S_PORT I2S_NUM_1

int32_t dc_offset = 0;

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("=== INMP441 Microphone Test ===");
  Serial.println("Open Serial Plotter to see the waveform.");
  Serial.println("Talk or clap — you should see the wave spike.");

  i2s_config_t i2s_config = {
    .mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate          = 16000,
    .bits_per_sample      = I2S_BITS_PER_SAMPLE_32BIT,  // INMP441: 24-bit in 32-bit slot
    .channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count        = 8,
    .dma_buf_len          = 512,
    .use_apll             = false
  };

  i2s_pin_config_t pin_config = {
    .bck_io_num   = I2S_MIC_SCK,
    .ws_io_num    = I2S_MIC_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num  = I2S_MIC_SD
  };

  if (i2s_driver_install(MIC_I2S_PORT, &i2s_config, 0, NULL) != ESP_OK) {
    Serial.println("ERROR: Failed to install I2S driver!");
    while (true) delay(1000);
  }

  if (i2s_set_pin(MIC_I2S_PORT, &pin_config) != ESP_OK) {
    Serial.println("ERROR: Failed to set I2S pins!");
    while (true) delay(1000);
  }

  Serial.println("Microphone ready.");
}

void loop() {
  int32_t raw_sample = 0;
  size_t  bytes_read = 0;

  esp_err_t result = i2s_read(MIC_I2S_PORT, &raw_sample, sizeof(raw_sample), &bytes_read, portMAX_DELAY);

  if (result == ESP_OK && bytes_read > 0) {
    // INMP441 puts data in the upper 24 bits of the 32-bit I2S slot.
    // Shift right by 8 to get a centred 24-bit value.
    int32_t sample = raw_sample >> 8;

    // Remove DC offset with a slow-moving average (same technique as production code)
    dc_offset = (dc_offset * 99 + sample) / 100;
    int32_t centered = sample - dc_offset;

    // Print for Serial Plotter
    Serial.println(centered);
  }
}
