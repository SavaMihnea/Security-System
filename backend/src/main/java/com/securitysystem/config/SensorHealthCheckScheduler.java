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

        // Step 1: check the CENTRAL gateway heartbeat
        for (Sensor sensor : sensors) {
            if (sensor.getType() != Sensor.SensorType.CENTRAL) continue;
            if (sensor.getLastSeen() == null) continue;
            long age = now.getEpochSecond() - sensor.getLastSeen().getEpochSecond();
            if (age >= HEARTBEAT_TIMEOUT_SECONDS && sensor.getStatus() != Sensor.SensorStatus.OFFLINE) {
                sensor.setStatus(Sensor.SensorStatus.OFFLINE);
                sensorRepository.save(sensor);
                log.debug("Marked CENTRAL {} as OFFLINE (no heartbeat for {}s)", sensor.getNodeId(), age);
            }
        }

        // Step 2: if the gateway is offline, all nodes behind it are unreachable — mark them too
        boolean gatewayOffline = sensors.stream()
                .filter(s -> s.getType() == Sensor.SensorType.CENTRAL)
                .anyMatch(s -> s.getStatus() == Sensor.SensorStatus.OFFLINE);

        if (gatewayOffline) {
            for (Sensor sensor : sensors) {
                if (sensor.getType() == Sensor.SensorType.CENTRAL) continue;
                if (sensor.getStatus() != Sensor.SensorStatus.OFFLINE) {
                    sensor.setStatus(Sensor.SensorStatus.OFFLINE);
                    sensorRepository.save(sensor);
                    log.debug("Marked sensor {} ({}) as OFFLINE (gateway offline)", sensor.getNodeId(), sensor.getName());
                }
            }
            return;
        }

        // Step 3: gateway is up — apply 24h staleness timeout to event-driven nodes
        for (Sensor sensor : sensors) {
            if (sensor.getType() == Sensor.SensorType.CENTRAL) continue;
            if (sensor.getLastSeen() == null) continue;
            long age = now.getEpochSecond() - sensor.getLastSeen().getEpochSecond();
            if (age >= 86400L && sensor.getStatus() != Sensor.SensorStatus.OFFLINE) {
                sensor.setStatus(Sensor.SensorStatus.OFFLINE);
                sensorRepository.save(sensor);
                log.debug("Marked sensor {} ({}) as OFFLINE (no data for {}s)", sensor.getNodeId(), sensor.getName(), age);
            }
        }
    }
}
