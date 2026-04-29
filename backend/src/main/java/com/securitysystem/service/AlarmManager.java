package com.securitysystem.service;

import com.securitysystem.model.Sensor;
import com.securitysystem.model.SystemConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VOXWALL Security Matrix & Stage Logic
 *
 * Implements the Master Security Matrix that determines alerting actions
 * based on system arm mode and sensor type. Handles:
 *   - Entry delay (30s for doors in HOME/AWAY modes)
 *   - Stage 1 (AI voice deterrence via AISecurityService)
 *   - Stage 2 (Hardware siren via WebSocket relay command)
 *   - I2S audio conflict prevention: Stage 2 is gated behind DETERRANCE_COMPLETE
 *     from the Hub, ensuring the I2S audio stream is fully closed before the
 *     siren relay activates.
 *
 * Thread safety: session maps use ConcurrentHashMap because startStage1AI
 * dispatches async work via AISecurityService (@Async), and the timeout
 * watchdog runs on the scheduler thread concurrently with HTTP handlers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlarmManager {

    private final SimpMessagingTemplate messagingTemplate;
    private final AISecurityService aiSecurityService;

    private final Map<String, Instant> activeEntryDelays = new ConcurrentHashMap<>();
    private final Map<String, Stage1Session> activeStage1Sessions = new ConcurrentHashMap<>();

    private static final long ENTRY_DELAY_SECONDS = 20;
    private static final long AI_SESSION_TIMEOUT_SECONDS = 120;

    // ============================================================
    // Security Matrix
    // ============================================================

    /**
     * Master Security Matrix.
     * Returns the action to take for a given arm mode and sensor event type.
     */
    public SecurityAction evaluateSecurityMatrix(
            SystemConfig.ArmMode armMode,
            Sensor.SensorType sensorType,
            String sensorName) {

        SecurityAction action = new SecurityAction();
        action.sensorName = sensorName;
        action.shouldLogEvent = true;

        switch (armMode) {
            case DISARMED:
                action.shouldPlayChime = true;
                action.actionType = "LOG_AND_CHIME";
                break;

            case ARMED_HOME:
                if (sensorType == Sensor.SensorType.DOOR) {
                    action.shouldActivateStage1 = true;
                    action.entryDelaySeconds = ENTRY_DELAY_SECONDS;
                    action.shouldActivateStage2 = true;
                    action.actionType = "STAGE1_WITH_ENTRY_DELAY_THEN_STAGE2";
                } else if (sensorType == Sensor.SensorType.VIBRATION) {
                    action.shouldActivateStage1 = true;
                    action.entryDelaySeconds = 0;
                    action.shouldActivateStage2 = true;
                    action.actionType = "STAGE1_INSTANT_THEN_STAGE2";
                } else if (sensorType == Sensor.SensorType.MOTION) {
                    action.actionType = "LOG_ONLY";
                }
                break;

            case ARMED_HOME_NIGHT:
                if (sensorType == Sensor.SensorType.DOOR || sensorType == Sensor.SensorType.VIBRATION) {
                    action.shouldActivateStage2 = true;
                    action.entryDelaySeconds = 0;
                    action.actionType = "STAGE2_INSTANT";
                } else if (sensorType == Sensor.SensorType.MOTION) {
                    action.actionType = "LOG_ONLY";
                }
                break;

            case ARMED_AWAY:
                if (sensorType == Sensor.SensorType.DOOR
                        || sensorType == Sensor.SensorType.VIBRATION
                        || sensorType == Sensor.SensorType.MOTION) {
                    action.shouldActivateStage1 = true;
                    action.entryDelaySeconds = ENTRY_DELAY_SECONDS;
                    action.shouldActivateStage2 = true;
                    action.actionType = "STAGE1_WITH_ENTRY_DELAY_THEN_STAGE2";
                }
                break;

            default:
                action.actionType = "UNKNOWN";
        }

        return action;
    }

    // ============================================================
    // Action Execution
    // ============================================================

    /**
     * Execute the security action determined by the matrix.
     * Returns immediately — Stage 1 audio generation is async.
     */
    public void executeSecurityAction(SecurityAction action, String nodeId, String sensorName) {
        log.info("[ALARM] Executing: {} for {}", action.actionType, sensorName);

        if (action.shouldActivateStage1) {
            startStage1AI(nodeId, sensorName, action.entryDelaySeconds);
        } else if (action.shouldActivateStage2) {
            activateSiren();
        }

        if (action.shouldPlayChime) {
            playChime();
        }
    }

    /**
     * Stage 1: kick off AI voice deterrence.
     *
     * Generates a unique sessionId, registers the session, then hands off to
     * AISecurityService which calls OpenAI TTS and pushes the result to
     * /topic/ai-audio asynchronously. This method returns in <1ms.
     *
     * Stage 2 fires only when the Hub sends DETERRANCE_COMPLETE back to
     * /app/hub/deterrance-complete — see HubCommunicationController.
     */
    private void startStage1AI(String nodeId, String sensorName, long entryDelaySeconds) {
        String sessionId = nodeId + "_" + System.currentTimeMillis();

        Stage1Session session = new Stage1Session();
        session.nodeId = nodeId;
        session.sessionId = sessionId;
        session.sensorName = sensorName;
        session.startedAt = Instant.now();
        session.entryDelayExpiresAt = entryDelaySeconds > 0
                ? Instant.now().plusSeconds(entryDelaySeconds)
                : Instant.now();

        activeStage1Sessions.put(nodeId, session);

        log.info("[STAGE1] Deterrence triggered — node={} sensor={} delay={}s session={}",
                nodeId, sensorName, entryDelaySeconds, sessionId);

        if (entryDelaySeconds > 0) {
            broadcastAlarmPending(nodeId, sensorName, entryDelaySeconds, sessionId);
        }

        aiSecurityService.generateAndBroadcastInitialDeterrence(
                sessionId, nodeId, sensorName, entryDelaySeconds, AI_SESSION_TIMEOUT_SECONDS);
    }

    /**
     * Stage 1 → Stage 2 transition.
     * Called by HubCommunicationController when it receives DETERRANCE_COMPLETE
     * with any reason other than USER_DISARMED.
     *
     * CRITICAL: the Hub must close its I2S audio stream before sending
     * DETERRANCE_COMPLETE. This method only fires after that confirmation,
     * preventing I2S bus conflicts on the ESP32-S3.
     */
    public void transitionToStage2(String nodeId) {
        Stage1Session session = activeStage1Sessions.remove(nodeId);
        if (session != null) {
            aiSecurityService.clearSession(session.sessionId);
            log.info("[STAGE2] Stage 1 complete for {} — activating siren", session.sensorName);
        }
        activateSiren();
    }

    /**
     * Watchdog: if the Hub goes silent (disconnected, crashed, power loss),
     * Stage 2 must still fire after AI_SESSION_TIMEOUT_SECONDS.
     * Runs every 5 seconds on the Spring scheduler thread.
     */
    @Scheduled(fixedDelay = 5000)
    public void checkStage1Timeouts() {
        Instant cutoff = Instant.now().minusSeconds(AI_SESSION_TIMEOUT_SECONDS);
        activeStage1Sessions.entrySet().removeIf(entry -> {
            Stage1Session session = entry.getValue();
            if (session.startedAt.isBefore(cutoff)) {
                log.warn("[ALARM] Stage 1 timed out for {} — forcing Stage 2", session.sensorName);
                aiSecurityService.clearSession(session.sessionId);
                activateSiren();
                return true;
            }
            return false;
        });
    }

    // ============================================================
    // Siren & Chime
    // ============================================================

    private void activateSiren() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("command", "ACTIVATE_SIREN");
        msg.put("timestamp", Instant.now().toString());
        try {
            messagingTemplate.convertAndSend("/topic/siren-control", msg);
            log.warn("[STAGE2] SIREN ACTIVATED");
        } catch (Exception e) {
            log.error("[STAGE2] Failed to activate siren", e);
        }
    }

    public void deactivateSiren() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("command", "DEACTIVATE_SIREN");
        msg.put("timestamp", Instant.now().toString());
        try {
            messagingTemplate.convertAndSend("/topic/siren-control", msg);
            log.info("[STAGE2] Siren deactivated");
        } catch (Exception e) {
            log.error("[STAGE2] Failed to deactivate siren", e);
        }
        activeStage1Sessions.clear();
        activeEntryDelays.clear();
    }

    private void playChime() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("command", "PLAY_CHIME");
        msg.put("timestamp", Instant.now().toString());
        try {
            messagingTemplate.convertAndSend("/topic/audio-control", msg);
            log.debug("[CHIME] Played");
        } catch (Exception e) {
            log.error("[CHIME] Failed to play", e);
        }
    }

    /**
     * Notifies the dashboard that a door event is in its entry-delay window.
     * The dashboard CountdownTimer subscribes to /topic/alarm-pending and
     * starts a visual countdown so the user can disarm before AI triggers.
     * Only sent when entryDelaySeconds > 0 (i.e. DOOR events in HOME/AWAY modes).
     */
    private void broadcastAlarmPending(String nodeId, String sensorName,
                                       long entryDelaySeconds, String sessionId) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("command", "ALARM_PENDING");
        msg.put("nodeId", nodeId);
        msg.put("sensorName", sensorName);
        msg.put("entryDelaySeconds", entryDelaySeconds);
        msg.put("sessionId", sessionId);
        msg.put("timestamp", Instant.now().toString());
        try {
            messagingTemplate.convertAndSend("/topic/alarm-pending", msg);
            log.info("[ALARM] ALARM_PENDING broadcast — sensor={} delay={}s", sensorName, entryDelaySeconds);
        } catch (Exception e) {
            log.error("[ALARM] Failed to broadcast ALARM_PENDING", e);
        }
    }

    /**
     * Tells the dashboard to dismiss the countdown banner.
     * Sent when the user disarms during the entry-delay window.
     */
    private void broadcastAlarmCancelled(String nodeId) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("command", "ALARM_CANCELLED");
        msg.put("nodeId", nodeId);
        msg.put("timestamp", Instant.now().toString());
        try {
            messagingTemplate.convertAndSend("/topic/alarm-pending", msg);
            log.info("[ALARM] ALARM_CANCELLED broadcast for {}", nodeId);
        } catch (Exception e) {
            log.error("[ALARM] Failed to broadcast ALARM_CANCELLED", e);
        }
    }

    // ============================================================
    // State Queries & Cancellation
    // ============================================================

    public boolean isEntryDelayActive(String nodeId) {
        Stage1Session session = activeStage1Sessions.get(nodeId);
        return session != null && Instant.now().isBefore(session.entryDelayExpiresAt);
    }

    /**
     * Called when the user disarms during an active Stage 1 session.
     * Clears the AI conversation history and deactivates the siren (if it fired).
     */
    public void cancelEntryDelayAndAlarm(String nodeId) {
        Stage1Session session = activeStage1Sessions.remove(nodeId);
        if (session != null) {
            aiSecurityService.clearSession(session.sessionId);
            log.info("[ENTRY_DELAY] Alarm cancelled for {} (session: {})", nodeId, session.sessionId);
        }
        broadcastAlarmCancelled(nodeId);
        deactivateSiren();
    }

    // ============================================================
    // Data Classes
    // ============================================================

    public static class SecurityAction {
        public String actionType;
        public String sensorName;
        public boolean shouldLogEvent;
        public boolean shouldPlayChime;
        public boolean shouldActivateStage1;
        public boolean shouldActivateStage2;
        public long entryDelaySeconds;
    }

    public static class Stage1Session {
        public String nodeId;
        public String sessionId;
        public String sensorName;
        public Instant startedAt;
        public Instant entryDelayExpiresAt;
    }
}
