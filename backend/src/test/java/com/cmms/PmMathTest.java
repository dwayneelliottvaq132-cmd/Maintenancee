package com.cmms;

import static org.assertj.core.api.Assertions.assertThat;

import com.cmms.entity.Enums.FrequencyType;
import com.cmms.service.PmMath;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PmMathTest {
    @Test
    void computesNextDueDates() {
        LocalDate d = LocalDate.of(2026, 1, 31);
        assertThat(PmMath.nextDue(d, FrequencyType.DAYS, 10)).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(PmMath.nextDue(d, FrequencyType.WEEKS, 1)).isEqualTo(LocalDate.of(2026, 2, 7));
        assertThat(PmMath.nextDue(d, FrequencyType.MONTHS, 1)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(PmMath.nextDue(d, FrequencyType.METER, 100)).isNull();
    }
}
