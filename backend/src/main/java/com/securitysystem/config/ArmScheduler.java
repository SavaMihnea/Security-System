package com.securitysystem.config;

import com.securitysystem.model.SystemConfig;
import com.securitysystem.repository.SystemConfigRepository;
import com.securitysystem.service.SystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Checks the configured arm/disarm schedule every minute and applies it.
 * Times are stored as "HH:mm" strings in SystemConfig and compared against
 * the current local time (server timezone).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArmScheduler {

    private final SystemConfigRepository systemConfigRepository;
    private final SystemService systemService;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Scheduled(fixedDelay = 60_000)
    public void checkSchedule() {
        SystemConfig config = systemConfigRepository.findById(1L).orElse(null);
        if (config == null || !config.isScheduleEnabled()) return;

        String now = LocalTime.now().format(HH_MM);

        if (now.equals(config.getScheduleArmTime())
                && config.getArmMode() == SystemConfig.ArmMode.DISARMED) {
            SystemConfig.ArmMode mode = config.getScheduleArmMode() != null
                    ? config.getScheduleArmMode()
                    : SystemConfig.ArmMode.ARMED_HOME_NIGHT;
            log.info("[SCHEDULE] Auto-arming at {} — mode {}", now, mode);
            systemService.setArmMode(mode, "schedule");
        }

        if (now.equals(config.getScheduleDisarmTime())
                && config.getArmMode() != SystemConfig.ArmMode.DISARMED) {
            log.info("[SCHEDULE] Auto-disarming at {}", now);
            systemService.setArmMode(SystemConfig.ArmMode.DISARMED, "schedule");
        }
    }
}
