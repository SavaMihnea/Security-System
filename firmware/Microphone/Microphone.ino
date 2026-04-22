#include <driver/i2s.h>

#define I2S_MIC_SCK  15
#define I2S_MIC_WS   16
#define I2S_MIC_SD   17

// Variable to track the "resting point" of the microphone
int32_t dc_offset = 0; 

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("Initializing Microphone...");

  i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = 16000, 
    .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 8,
    .dma_buf_len = 512,
    .use_apll = false
  };

  i2s_pin_config_t pin_config = {
    .bck_io_num = I2S_MIC_SCK,
    .ws_io_num = I2S_MIC_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = I2S_MIC_SD
  };

  if (i2s_driver_install(I2S_NUM_0, &i2s_config, 0, NULL) != ESP_OK) {
    Serial.println("Failed to install I2S driver!");
    return;
  }
  
  if (i2s_set_pin(I2S_NUM_0, &pin_config) != ESP_OK) {
    Serial.println("Failed to set I2S pins!");
    return;
  }

  Serial.println("Microphone Ready! Open the Serial Plotter.");
}

void loop() {
  int32_t raw_sample = 0;
  size_t bytes_read = 0;

  esp_err_t result = i2s_read(I2S_NUM_0, &raw_sample, sizeof(raw_sample), &bytes_read, portMAX_DELAY);

  if (result == ESP_OK && bytes_read > 0) {
    int32_t normalized_sample = raw_sample >> 8; 

    // Find the DC Offset using a fast moving average
    // It takes 99% of the old resting point, and 1% of the new reading
    dc_offset = (dc_offset * 99 + normalized_sample) / 100;

    // Subtract the resting point from the actual reading to center it at 0
    int32_t centered_wave = normalized_sample - dc_offset;

    Serial.println(centered_wave);
  }
}