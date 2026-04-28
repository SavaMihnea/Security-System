# 🛡️ VOXWALL — IoT Security System

A distributed home security system built as a 4th-year university thesis. Sensor nodes communicate wirelessly to a central unit, which talks to a cloud backend. When an alarm triggers, an AI agent speaks to the intruder through a speaker and holds a live conversation.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.4.4, Spring Security, JPA/Hibernate |
| Database | MySQL (auto-migrated by Hibernate) |
| Auth | JWT (HMAC-SHA256, 24h expiry) + BCrypt passwords |
| Real-time | WebSocket (STOMP + SockJS) |
| Frontend | HTML5 / CSS3 / vanilla JS + Bootstrap 5.3.3 |
| AI Pipeline | OpenAI Whisper → GPT-4o → TTS (voice: onyx) |
| Firmware | Arduino IDE, ESP32-S3 (central), ESP32-C3 (sensor nodes) |
| Wireless | ESP-NOW (node → central), HTTPS over WiFi (central → cloud) |
| Hosting | Railway or Render (free tier) |

---

## How It Works

```
[ESP32-C3 Sensor Node]  ─┐
[ESP32-C3 Sensor Node]   ├─ ESP-NOW ──► [ESP32-S3 Central Unit] ── HTTPS ──► [Spring Boot Backend]
[ESP32-C3 Sensor Node]  ─┘                                                           │
                                                                          ┌───────────┴───────────┐
                                                                       REST API             WebSocket
                                                                          └───────────┬───────────┘
                                                                                      ▼
                                                                              [Browser Dashboard]
```

- **Sensor nodes** detect motion / vibration / door open and send events to the central unit via ESP-NOW (no router required between nodes)
- **Central unit** forwards events to the backend over HTTPS, controls the siren relay, and runs the AI conversation
- **Backend** stores events, manages arm state, and runs the OpenAI pipeline
- **Dashboard** lets you arm/disarm, view live sensor status, and browse event history in real time

---

## Arm Modes

| Mode | Behaviour |
|---|---|
| **Disarmed** | Sensors logged silently, no alarm |
| **Armed Home** | System on but sensors ignored — you're home and awake |
| **Armed Night** | All sensors active, alarm triggers instantly — you're sleeping |
| **Armed Away** | All sensors active, 10-second entry delay on door events |

---

## AI Deterrence (on alarm)

1. Siren activates
2. ESP32 requests a deterrence phrase → played on speaker immediately
3. Mic monitors for sound:
   - **Sound detected** → records 5s WAV → Whisper transcribes → GPT-4o responds → TTS plays response → repeat
   - **4s of silence** → plays a proactive deterrence statement → repeat
4. Loop runs until the system is disarmed remotely via the dashboard

---

## Security Features

- **BCrypt** password hashing (never plain text)
- **JWT** auth on all dashboard API calls
- **ESP API key** (`X-ESP-Key` header) on all ESP32 endpoints — returns 401 on mismatch
- **CORS** locked to a single configured origin (not open wildcard)
- **Brute-force protection** — account locked for 15 minutes after 5 failed login attempts (HTTP 423)
- **HTTPS** for all ESP32 ↔ backend traffic

---

## Project Structure

```
backend/
  src/main/java/com/securitysystem/
    config/         → Security, WebSocket, data initializer
    controller/     → Auth, Sensor, Event, System, ESP32, AI endpoints
    service/        → Business logic + OpenAI pipeline
    model/          → JPA entities (User, Sensor, Event, SystemConfig)
    security/       → JWT filter and utility
  src/main/resources/
    application.properties   → All config and secrets
    static/                  → Frontend HTML/CSS/JS (served by Spring Boot)

firmware/
  central-unit/central-unit.ino   → ESP32-S3: ESP-NOW, relay, AI loop
  sensor-node/sensor-node.ino     → ESP32-C3: sensor reading, ESP-NOW send
```

---

## Getting Started

### 1. Backend (local)

- Install **Java 21** and **MySQL**
- Edit `backend/src/main/resources/application.properties`:
  ```
  spring.datasource.password = YOUR_DB_PASSWORD
  jwt.secret                 = a-random-32+-char-string
  esp.api-key                = a-random-key-you-invent
  openai.api-key             = sk-...
  cors.allowed-origin        = http://localhost:8080
  ```
- Run from IntelliJ IDEA or: `mvn spring-boot:run`
- Open `http://localhost:8080/login.html` — login: `admin / admin123`

### 2. Backend (Railway cloud)

- Push the `backend/` folder to GitHub
- Create a new Railway project, connect the repo
- Add the same values above as Railway environment variables
- Set `cors.allowed-origin` to your Railway URL

### 3. Firmware

- Open `firmware/central-unit/central-unit.ino` in Arduino IDE
- Set `WIFI_SSID`, `WIFI_PASSWORD`, `BACKEND_URL`, `ESP_API_KEY`
- Flash to **ESP32-S3** (board: "ESP32S3 Dev Module")
- Read the MAC address from Serial Monitor, paste into `sensor-node.ino`
- Configure each node (`SENSOR_TYPE`, `SENSOR_PIN`, `NODE_NAME`) and flash to **ESP32-C3**

---

## Hardware (Central Unit pins)

| GPIO | Connected to |
|---|---|
| 2 | Status LED (via 220Ω) |
| 4 | INMP441 mic WS |
| 5 | INMP441 mic SCK |
| 6 | INMP441 mic SD |
| 10 | Relay IN1 (siren) |
| 12 | MAX98357A BCLK (speaker) |
| 13 | MAX98357A LRC (speaker) |
| 14 | MAX98357A DIN (speaker) |

See **WIRING_GUIDE.txt** for full wiring instructions including all sensor node types.

---
