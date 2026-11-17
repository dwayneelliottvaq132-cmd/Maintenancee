package com.cmms.service;

import com.cmms.entity.Enums.FrequencyType;
import java.time.LocalDate;

/** Date arithmetic for preventive maintenance intervals. Mirrored in worker/cmms_worker/pm.py. */
public final class PmMath {
    private PmMath() {}

    public static LocalDate nextDue(LocalDate from, FrequencyType type, int interval) {
        return switch (type) {
            case DAYS -> from.plusDays(interval);
            case WEEKS -> from.plusWeeks(interval);
            case MONTHS -> from.plusMonths(interval);
            case METER -> null;
        };
    }
}
