#include <driver/i2s.h>
#include <math.h>

// --- I2S SPEAKER PINS ---
#define I2S_SPK_BCLK  12
#define I2S_SPK_LRC   13
#define I2S_SPK_DOUT  14

#define SAMPLE_RATE 16000
#define TONE_FREQ   440 // The musical note A4

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("Testing Speaker: Generating pure 440Hz Tone...");

  i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    // Sending stereo ensures the amp plays it regardless of how it's wired
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

  // Setup I2S channel 0 for the speaker
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
  // Create a buffer for stereo audio (Left and Right channels)
  int16_t sample_buffer[256]; 
  static float phase = 0;
  float phase_increment = (2.0 * PI * TONE_FREQ) / SAMPLE_RATE;

  for (int i = 0; i < 256; i += 2) {
    // Generate sine wave.
    // Amplitude 28000 / 32767 ≈ 85% of max — loud enough to actually test the speaker.
    int16_t sample = (int16_t)(sin(phase) * 28000);
    
    sample_buffer[i] = sample;     // Left Channel
    sample_buffer[i+1] = sample;   // Right Channel
    
    phase += phase_increment;
    if (phase >= 2.0 * PI) {
      phase -= 2.0 * PI;
    }
  }

  // Push the math to the speaker
  size_t bytes_written;
  i2s_write(I2S_NUM_0, sample_buffer, sizeof(sample_buffer), &bytes_written, portMAX_DELAY);
}