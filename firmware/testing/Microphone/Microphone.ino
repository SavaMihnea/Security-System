#include <driver/i2s.h>

// Pins must match central-unit.ino exactly
#define I2S_MIC_SCK  5   // INMP441 SCK  → GPIO 5
#define I2S_MIC_WS   4   // INMP441 WS   → GPIO 4
#define I2S_MIC_SD   6   // INMP441 SD   → GPIO 6

#define MIC_I2S_PORT    I2S_NUM_1
#define SAMPLE_RATE     16000

// One amplitude reading per 50 ms window (800 samples at 16 kHz).
// At 50 ms per print the Serial port handles it easily and the
// Serial Plotter shows a clean loudness-meter envelope instead of
// a wall of noise.
#define SAMPLES_PER_WINDOW 800

// Running DC offset — removes the mic's slow bias so the peak
// measurement reflects real audio content, not the baseline drift.
static int32_t g_dc = 0;

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("=== INMP441 Amplitude Meter ===");
  Serial.println("Open Serial Plotter.");
  Serial.println("Silence = flat low line.  Clap or speak = clear spike.");
  Serial.println("If nothing moves at all, check SCK/WS/SD wiring and L/R pin (must be tied to GND).");

  i2s_config_t config = {
    .mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate          = SAMPLE_RATE,
    .bits_per_sample      = I2S_BITS_PER_SAMPLE_32BIT,
    .channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count        = 8,
    .dma_buf_len          = 512,
    .use_apll             = false
  };

  i2s_pin_config_t pins = {
    .bck_io_num   = I2S_MIC_SCK,
    .ws_io_num    = I2S_MIC_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num  = I2S_MIC_SD
  };

  if (i2s_driver_install(MIC_I2S_PORT, &config, 0, NULL) != ESP_OK) {
    Serial.println("ERROR: i2s_driver_install failed!");
    while (true) delay(1000);
  }
  if (i2s_set_pin(MIC_I2S_PORT, &pins) != ESP_OK) {
    Serial.println("ERROR: i2s_set_pin failed!");
    while (true) delay(1000);
  }

  Serial.println("Mic ready. Waiting for sound...");
}

void loop() {
  int32_t peak = 0;
  int32_t sample;
  size_t  bytes_read;

  // Read one 50 ms window, track DC, find peak of centred signal
  for (int i = 0; i < SAMPLES_PER_WINDOW; i++) {
    i2s_read(MIC_I2S_PORT, &sample, sizeof(sample), &bytes_read, portMAX_DELAY);

    // INMP441 data is left-justified (audio in upper 24 bits of 32-bit slot)
    int32_t val = sample >> 8;

    // Slow DC filter — converges in ~100 ms, then tracks any long-term drift
    g_dc = (g_dc * 99 + val) / 100;
    int32_t centred = val - g_dc;

    if (abs(centred) > peak) peak = abs(centred);
  }

  // One value per 50 ms → Serial Plotter shows a smooth amplitude envelope.
  // Also print a text bar to Serial Monitor for quick threshold calibration.
  int bars = peak / 500;
  if (bars > 40) bars = 40;
  char bar[42];
  memset(bar, '|', bars);
  bar[bars] = '\0';
  Serial.printf("amp=%6d  %s\n", peak, bar);

  // Uncomment this line instead if you only want the clean Serial Plotter graph:
  // Serial.println(peak);
}
