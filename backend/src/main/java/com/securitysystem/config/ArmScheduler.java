package com.securitysystem.config;

import com.securitysystem.model.SystemConfig;
import com.securitysystem.repository.SystemConfigRepository;
import com.securitysystem.service.SystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;

/**
 * Checks the configured arm/disarm schedule every minute and applies it.
 * Each arm mode has its own independent arm/disarm times.
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
    private static final Map<DayOfWeek, String> DAY_ABBR = Map.of(
        DayOfWeek.MONDAY,    "MON",
        DayOfWeek.TUESDAY,   "TUE",
        DayOfWeek.WEDNESDAY, "WED",
        DayOfWeek.THURSDAY,  "THU",
        DayOfWeek.FRIDAY,    "FRI",
        DayOfWeek.SATURDAY,  "SAT",
        DayOfWeek.SUNDAY,    "SUN"
    );

    @Scheduled(fixedDelay = 60_000)
    public void checkSchedule() {
        SystemConfig config = systemConfigRepository.findById(1L).orElse(null);
        if (config == null || !config.isScheduleEnabled()) return;

        String scheduleDays = config.getScheduleDays();
        if (scheduleDays != null && !scheduleDays.isBlank()) {
            String today = DAY_ABBR.get(LocalDate.now().getDayOfWeek());
            if (!Arrays.asList(scheduleDays.split(",")).contains(today)) return;
        }

        String now = LocalTime.now().format(HH_MM);
        SystemConfig.ArmMode current = config.getArmMode();

        // ARM NIGHT — arms when DISARMED, disarms only when currently in NIGHT mode
        if (now.equals(config.getScheduleNightArmTime()) && current == SystemConfig.ArmMode.DISARMED) {
            log.info("[SCHEDULE] Auto-arming NIGHT at {}", now);
            systemService.setArmMode(SystemConfig.ArmMode.ARMED_HOME_NIGHT, "schedule");
            return;
        }
        if (now.equals(config.getScheduleNightDisarmTime()) && current == SystemConfig.ArmMode.ARMED_HOME_NIGHT) {
            log.info("[SCHEDULE] Auto-disarming from NIGHT at {}", now);
            systemService.setArmMode(SystemConfig.ArmMode.DISARMED, "schedule");
            return;
        }

        // ARM HOME — arms when DISARMED, disarms only when currently in HOME mode
        if (now.equals(config.getScheduleHomeArmTime()) && current == SystemConfig.ArmMode.DISARMED) {
            log.info("[SCHEDULE] Auto-arming HOME at {}", now);
            systemService.setArmMode(SystemConfig.ArmMode.ARMED_HOME, "schedule");
            return;
        }
        if (now.equals(config.getScheduleHomeDisarmTime()) && current == SystemConfig.ArmMode.ARMED_HOME) {
            log.info("[SCHEDULE] Auto-disarming from HOME at {}", now);
            systemService.setArmMode(SystemConfig.ArmMode.DISARMED, "schedule");
            return;
        }

        // ARM AWAY — arms when DISARMED, disarms only when currently in AWAY mode
        if (now.equals(config.getScheduleAwayArmTime()) && current == SystemConfig.ArmMode.DISARMED) {
            log.info("[SCHEDULE] Auto-arming AWAY at {}", now);
            systemService.setArmMode(SystemConfig.ArmMode.ARMED_AWAY, "schedule");
        }
        if (now.equals(config.getScheduleAwayDisarmTime()) && current == SystemConfig.ArmMode.ARMED_AWAY) {
            log.info("[SCHEDULE] Auto-disarming from AWAY at {}", now);
            systemService.setArmMode(SystemConfig.ArmMode.DISARMED, "schedule");
        }
    }
}
