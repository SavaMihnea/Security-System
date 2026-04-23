package com.securitysystem.config;

import com.securitysystem.model.Sensor;
import com.securitysystem.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodically checks sensor heartbeats and marks as OFFLINE if not seen in 90+ seconds.
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class SensorHealthCheckScheduler {

    private final SensorRepository sensorRepository;
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 90;

    @Scheduled(fixedDelay = 30000) // Check every 30 seconds
    public void checkSensorHealth() {
        Instant now = Instant.now();
        List<Sensor> sensors = sensorRepository.findAll();

        for (Sensor sensor : sensors) {
            if (sensor.getLastSeen() == null) continue;

            long secondsSinceLastSeen = (now.getEpochSecond() - sensor.getLastSeen().getEpochSecond());

            // Mark as OFFLINE if no heartbeat for 90+ seconds and currently not OFFLINE
            if (secondsSinceLastSeen >= HEARTBEAT_TIMEOUT_SECONDS 
                    && sensor.getStatus() != Sensor.SensorStatus.OFFLINE) {
                sensor.setStatus(Sensor.SensorStatus.OFFLINE);
                sensorRepository.save(sensor);
                log.debug("Marked sensor {} ({}) as OFFLINE (no heartbeat for {} seconds)",
                        sensor.getNodeId(), sensor.getName(), secondsSinceLastSeen);
            }
        }
    }
}
