# VOXWALL Security System - Implementation Guide

## Deliverables Summary

### Task 1: Sensor Node Firmware (✅ Complete)

Three production-ready firmware files created in `/firmware/sensor-node/`:

#### **Door_Node.ino**
- **Hardware**: ESP32-C3 Super Mini
- **Sensor**: MC-38 Magnetic Door Switch
- **Pin**: GPIO 4 (INPUT_PULLUP)
- **Trigger**: LOW (magnet present = closed)
- **Cooldown**: 5 seconds
- **Events Sent**:
  - `DOOR_OPENED` - when magnet separates
  - `DOOR_CLOSED` - when magnet attaches
- **Configuration**: Edit lines 18-21 before flashing
  - `NODE_NAME`: Display name on dashboard
  - `NODE_LOCATION`: Physical location
  - `centralUnitMac[]`: S3 Gateway MAC address

#### **Motion_Node.ino**
- **Hardware**: ESP32-C3 Super Mini
- **Sensor**: HC-SR501 PIR Motion Detector
- **Pin**: GPIO 4 (INPUT, push-pull output)
- **Trigger**: HIGH (motion detected)
- **Cooldown**: 30 seconds
- **Events Sent**:
  - `MOTION_DETECTED` - when motion is detected (once per cooldown window)
- **Configuration**: Edit lines 18-21 before flashing

#### **Vibration_Node.ino**
- **Hardware**: ESP32-C3 Super Mini
- **Sensor**: 801S Vibration/Shock Switch
- **Pin**: GPIO 4 (INPUT_PULLUP)
- **Trigger**: LOW (vibration detected)
- **Cooldown**: 5 seconds
- **Events Sent**:
  - `VIBRATION_DETECTED` - when vibration/shock triggers
- **Configuration**: Edit lines 18-21 before flashing

**Common Configuration for All Nodes:**
```cpp
// Before flashing, set the S3 gateway MAC address:
uint8_t centralUnitMac[] = {0x10, 0xB4, 0x1D, 0xE8, 0xE8, 0xC0};  // Update this!

// Arduino IDE: Tools -> USB CDC On Boot: Enabled
```

**ESP-NOW Message Structure (all nodes):**
```cpp
typedef struct __attribute__((packed)) {
    char nodeId[30];      // Auto-generated from MAC: "NODE_AABBCCDDEEFF"
    char eventType[40];   // "DOOR_OPENED", "MOTION_DETECTED", etc.
    int  sensorValue;     // Raw sensor reading or 0/1 flag
} SensorMessage;
```

---

### Task 2: Backend Security Logic (✅ Complete)

Three new/updated Java services implement the Master Security Matrix.

#### **1. AlarmManager.java** (New Service)
**Purpose**: Evaluates the security matrix and coordinates Stage 1 (AI) and Stage 2 (Siren) transitions.

**Master Security Matrix Implementation:**

| System Mode | Door (Magnet) | Window (Vibration) | Motion (PIR) |
|---|---|---|---|
| **DISARMED** | Log + Chime | Log + Chime | Log Only |
| **ARMED_HOME** | Stage1(AI) + 30s delay → Stage2(Siren) | Stage1(AI) instant → Stage2(Siren) | Log Only |
| **ARMED_HOME_NIGHT** | Stage2(Siren) instant | Stage2(Siren) instant | Log Only |
| **ARMED_AWAY** | Stage1(AI) + 30s delay → Stage2(Siren) | Stage1(AI) instant → Stage2(Siren) | Stage1(AI) instant → Stage2(Siren) |

**Key Methods:**

```java
// Evaluate what action to take
public SecurityAction evaluateSecurityMatrix(
    SystemConfig.ArmMode armMode,
    Sensor.SensorType sensorType,
    String sensorName)

// Execute the determined action
public void executeSecurityAction(
    SecurityAction action,
    String nodeId,
    String sensorName)

// Transition from AI to Siren (called when AI audio finishes)
public void transitionToStage2(String nodeId)

// Manually cancel alarm during entry delay
public void cancelEntryDelayAndAlarm(String nodeId)
```

**Entry Delay Handling:**
- Doors in ARMED_HOME and ARMED_AWAY modes get a 30-second grace period
- During entry delay, Stage 1 (AI) activates but Stage 2 (Siren) waits
- User can disarm during the window to cancel the alarm
- If entry delay expires, automatically transition to Stage 2

**Stage Coordination:**
- **Stage 1 (AI Intervention)**: S3 Hub voice agent attempts to persuade intruder
  - Utilizes I2S_NUM_0 (MAX98357A speaker)
  - Duration: 0-120 seconds (timeout after 2 minutes)
  - Triggered via WebSocket: `/topic/ai-control`
- **Stage 2 (Siren)**: Hardware siren via relay
  - GPIO 10 on S3 (active HIGH)
  - Only activates after Stage 1 audio stream is CLOSED
  - Triggered via WebSocket: `/topic/siren-control`

#### **2. EventService.java** (Updated)
**New Method**: `processIncomingEvent()`
- Replaces simple logging with security matrix evaluation
- Called when sensor sends DOOR_OPENED, MOTION_DETECTED, VIBRATION_DETECTED
- Always logs to database
- Triggers AlarmManager based on arm mode + sensor type

```java
public EventDto processIncomingEvent(
    String nodeId,           // Sensor node ID
    String eventTypeStr,     // "DOOR_OPENED", "MOTION_DETECTED", etc.
    String notes)            // Additional context
```

#### **3. HubCommunicationController.java** (New WebSocket Controller)
**Purpose**: Bidirectional communication between backend and S3 Hub for Stage coordination.

**Backend → Hub (Topic-based, automatic):**
```
/topic/ai-control        - Start AI conversation
  {
    "command": "START_AI",
    "nodeId": "NODE_XXXXXX",
    "sensorName": "Front Door",
    "entryDelaySeconds": 30,
    "maxDurationSeconds": 120
  }

/topic/siren-control     - Activate/Deactivate siren
  {
    "command": "ACTIVATE_SIREN" | "DEACTIVATE_SIREN",
    "timestamp": "2026-04-28T14:30:00Z"
  }

/topic/audio-control     - Play chime (DISARMED mode)
  {
    "command": "PLAY_CHIME",
    "timestamp": "2026-04-28T14:30:00Z"
  }
```

**Hub → Backend (Message Mapping):**

```
/app/hub/ai-complete
  {
    "nodeId": "NODE_XXXXXX",
    "status": "COMPLETED",
    "reason": "USER_DISARMED" | "TIMEOUT" | "NATURAL_COMPLETION",
    "durationSeconds": 45,
    "timestamp": "2026-04-28T14:30:45Z"
  }
  → Backend evaluates: If USER_DISARMED, cancel alarm. Otherwise, Stage 2.

/app/hub/entry-delay-expired
  {
    "nodeId": "NODE_XXXXXX",
    "event": "ENTRY_DELAY_EXPIRED",
    "timestamp": "2026-04-28T14:31:15Z"
  }
  → Backend transitions to Stage 2 (siren).

/app/hub/siren-status
  {
    "status": "ACTIVATED" | "DEACTIVATED",
    "timestamp": "2026-04-28T14:31:20Z"
  }
  → Backend broadcasts to dashboard.

/app/hub/heartbeat
  {
    "timestamp": 1722181860000
  }
  → Health check, logged at DEBUG level.
```

---

## Critical I2S Audio Bus Constraint

**Problem**: ESP32-S3 has one I2S bus. Audio playback (Stage 1) and relay control cannot conflict.

**Solution Implemented**:
1. Stage 1 uses `I2S_NUM_0` (ESP32-audioI2S library)
2. Hub MUST close the I2S audio stream before responding with `ai-complete`
3. Backend ONLY sends siren command after receiving `ai-complete` from hub
4. This prevents simultaneous I2S transactions

**Central Unit Firmware Pseudocode:**
```cpp
void onAIComplete() {
    // Close I2S audio stream FIRST
    audio.stopSong();  // ESP32-audioI2S
    delay(100);        // Allow I2S to fully release

    // THEN notify backend
    sendMessage("/app/hub/ai-complete", { 
        "reason": "NATURAL_COMPLETION" 
    });
    
    // Wait for siren activation via WebSocket
}
```

---

## Database Schema Updates

**Events table** (existing, used by security logic):
- `eventType`: ENUM (DOOR_OPENED, MOTION_DETECTED, VIBRATION_DETECTED, etc.)
- `timestamp`: INSTANT (UTC, ISO 8601 format)
- `sensor_id`: Foreign key to sensors table
- `resolved`: Boolean flag

**Sensors table** (existing, used for monitoring):
- `type`: ENUM (DOOR, MOTION, VIBRATION, CENTRAL)
- `status`: ENUM (ONLINE, OFFLINE, TRIGGERED, FAULT)
- `lastSeen`: INSTANT (UTC)

**System Config table** (existing, used by alarm logic):
- `armMode`: ENUM (DISARMED, ARMED_HOME, ARMED_HOME_NIGHT, ARMED_AWAY)
- `armed`: Boolean
- `lastUpdated`: INSTANT (UTC)

---

## Testing Checklist

### Unit Test - Security Matrix
```
✓ DISARMED + DOOR_OPENED       → Log + Chime
✓ ARMED_HOME + DOOR_OPENED     → Stage1(30s delay) + Stage2
✓ ARMED_HOME + VIBRATION_DETECTED → Stage1(instant) + Stage2
✓ ARMED_HOME + MOTION_DETECTED → Log Only
✓ ARMED_HOME_NIGHT + DOOR_OPENED → Stage2(instant)
✓ ARMED_AWAY + MOTION_DETECTED → Stage1(instant) + Stage2
```

### Integration Test - Stage Coordination
```
✓ Send DOOR_OPENED in ARMED_HOME
  → Backend sends /topic/ai-control
  → Hub receives, starts audio
  → 30s later or on completion, Hub sends /app/hub/ai-complete
  → Backend receives, sends /topic/siren-control
  → Verify relay activates

✓ Send VIBRATION_DETECTED in ARMED_HOME
  → Backend sends /topic/ai-control with entryDelaySeconds=0
  → Hub starts audio immediately (no delay)
  → Verify Stage 2 activates after AI complete

✓ User disarms during entry delay
  → Hub receives disarm, sends /app/hub/ai-complete with "USER_DISARMED"
  → Backend receives, calls cancelEntryDelayAndAlarm()
  → Verify siren is NOT activated
```

### E2E Test - Full Alarm Sequence
```
1. Arm system in ARMED_AWAY
2. Open door → Sensor sends DOOR_OPENED
3. Backend processes matrix → Stage1 activates
4. S3 plays AI audio ("Please leave the premises...")
5. After 45s, AI completes → Hub sends /app/hub/ai-complete
6. Backend receives → Triggers Stage2
7. S3 activates siren relay
8. Dashboard shows alarm active, events logged
9. User disarms → Siren stops, alarm resets
```

---

## Deployment Notes

1. **Firmware**: Flash Door_Node, Motion_Node, Vibration_Node to three separate C3 boards
2. **Central Unit**: Ensure central-unit.ino updated to send WebSocket messages to backend
3. **Backend**: Restart Spring Boot app (new AlarmManager, EventService, HubCommunicationController)
4. **Database**: No migration needed (uses existing schema)
5. **Frontend**: Dashboard already subscribes to `/topic/events` for real-time updates

---

## Architecture Diagram

```
[Sensor Nodes (C3)]
    ↓ (ESP-NOW on Ch 13)
[Central Unit (S3)]
    ↓ (HTTPS POST + WebSocket)
[Java Backend]
    ├─ EventService (logs + routes to AlarmManager)
    ├─ AlarmManager (evaluates matrix, coordinates stages)
    └─ HubCommunicationController (WebSocket bidirectional)
    ↓ (WebSocket topics)
[S3 Hub]
    ├─ Stage 1: AI Voice (I2S_NUM_0)
    └─ Stage 2: Siren Relay (GPIO 10)
    
[Dashboard]
    ← WebSocket /topic/events (real-time UI updates)
```

---

## Future Enhancements

- [ ] Entry delay customization per sensor type
- [ ] AI response logging for behavioral analysis
- [ ] SMS/Push notifications on alarm trigger
- [ ] Multi-user disarm permissions
- [ ] Siren volume/duration control
- [ ] Environmental sensor integration (temperature, smoke)
- [ ] Machine learning for false positive filtering
