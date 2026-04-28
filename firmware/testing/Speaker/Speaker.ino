#include <driver/i2s.h>
#include <math.h>

// --- I2S SPEAKER PINS (Matches your Wiring Guide) ---
#define I2S_SPK_BCLK  12
#define I2S_SPK_LRC   13
#define I2S_SPK_DOUT  14

#define SAMPLE_RATE 16000
#define TONE_FREQ   440 

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("Testing Speaker: Low-Volume Safety Test...");

  i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 8,
    .dma_buf_len = 512,
    .use_apll = false
  };

  i2s_pin_config_t pin_config = {
    .bck_io_num = I2S_SPK_BCLK,
    .ws_io_num = I2S_SPK_LRC,
    .data_out_num = I2S_SPK_DOUT,
    .data_in_num = I2S_PIN_NO_CHANGE
  };

  if (i2s_driver_install(I2S_NUM_0, &i2s_config, 0, NULL) != ESP_OK) {
    Serial.println("Failed to install I2S driver!");
    return;
  }
  
  if (i2s_set_pin(I2S_NUM_0, &pin_config) != ESP_OK) {
    Serial.println("Failed to set I2S pins!");
    return;
  }
}

void loop() {
  int16_t sample_buffer[256]; 
  static float phase = 0;
  float phase_increment = (2.0 * PI * TONE_FREQ) / SAMPLE_RATE;

  for (int i = 0; i < 256; i += 2) {
    // 18000 / 32767 ≈ 55% of max — calibrated safe limit for this speaker + USB supply.
    int16_t sample = (int16_t)(sin(phase) * 18000);
    
    sample_buffer[i] = sample;     
    sample_buffer[i+1] = sample;   
    
    phase += phase_increment;
    if (phase >= 2.0 * PI) {
      phase -= 2.0 * PI;
    }
  }

  size_t bytes_written;
  i2s_write(I2S_NUM_0, sample_buffer, sizeof(sample_buffer), &bytes_written, portMAX_DELAY);
}