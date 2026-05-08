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
