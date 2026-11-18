package com.cmms.service;

import com.cmms.config.CmmsProperties;
import com.cmms.entity.*;
import com.cmms.entity.Enums.*;
import com.cmms.repo.*;
import com.cmms.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PM schedule maintenance and work-order generation. The nightly Python worker
 * (worker/cmms_worker/pm.py) applies the same rules; "Generate due now" in the UI and, when
 * CMMS_PM_SCHEDULER=true (Windows), the built-in timer use this class.
 */
@Service
public class PmService {
    private static final Logger log = LoggerFactory.getLogger(PmService.class);

    private final PmScheduleRepository schedules;
    private final WorkOrderRepository wos;
    private final AssetRepository assets;
    private final WorkOrderService woService;
    private final CmmsProperties props;
    private final PmService self;   // proxy, so each generate() call gets its own transaction

    public PmService(PmScheduleRepository schedules, WorkOrderRepository wos, AssetRepository assets,
                     WorkOrderService woService, CmmsProperties props, @Lazy PmService self) {
        this.schedules = schedules;
        this.wos = wos;
        this.assets = assets;
        this.woService = woService;
        this.props = props;
        this.self = self;
    }

    public PmSchedule get(Long id) {
        return schedules.findById(id).orElseThrow(() -> ApiException.notFound("PM schedule", id));
    }

    @Transactional
    public PmSchedule save(Long id, PmSchedule in) {
        if (in.title == null || in.title.isBlank()) throw ApiException.bad("Title is required");
        if (in.assetId == null) throw ApiException.bad("Asset is required");
        if (in.frequencyType == null || in.intervalValue <= 0) throw ApiException.bad("Frequency and a positive interval are required");
        if (in.leadDays < 0) throw ApiException.bad("Lead days cannot be negative");
        Asset asset = assets.findById(in.assetId).orElseThrow(() -> ApiException.notFound("Asset", in.assetId));
        PmSchedule s = id == null ? new PmSchedule() : get(id);
        s.title = in.title.trim();
        s.assetId = in.assetId;
        s.instructionId = in.instructionId;
        s.frequencyType = in.frequencyType;
        s.intervalValue = in.intervalValue;
        s.leadDays = in.leadDays;
        s.priority = in.priority == null ? Priority.MEDIUM : in.priority;
        s.defaultAssigneeId = in.defaultAssigneeId;
        s.active = in.active;
        if (s.frequencyType == FrequencyType.METER) {
            s.nextDue = null;
            s.nextMeter = in.nextMeter != null ? in.nextMeter : asset.meterReading.add(BigDecimal.valueOf(in.intervalValue));
        } else {
            s.nextMeter = null;
            s.nextDue = in.nextDue != null ? in.nextDue : (s.nextDue != null ? s.nextDue : LocalDate.now());
        }
        return schedules.save(s);
    }

    public boolean isDue(PmSchedule s, LocalDate today, Map<Long, Asset> assetsById) {
        if (!s.active) return false;
        if (s.frequencyType == FrequencyType.METER) {
            Asset a = assetsById.get(s.assetId);
            return s.nextMeter != null && a != null && a.meterReading.compareTo(s.nextMeter) >= 0;
        }
        return s.nextDue != null && !s.nextDue.minusDays(s.leadDays).isAfter(today);
    }

    public boolean hasOpenWorkOrder(Long scheduleId) {
        return wos.existsByPmScheduleIdAndStatusNotIn(scheduleId, WorkOrderService.CLOSED);
    }

    @Transactional
    public WorkOrder generate(PmSchedule s, Long createdBy) {
        if (hasOpenWorkOrder(s.id)) throw ApiException.conflict("An open work order already exists for this schedule");
        WorkOrder in = new WorkOrder();
        in.title = "PM: " + s.title;
        in.type = WoType.PM;
        in.priority = s.priority;
        in.assetId = s.assetId;
        in.instructionId = s.instructionId;
        in.pmScheduleId = s.id;
        in.dueDate = s.frequencyType == FrequencyType.METER ? LocalDate.now().plusDays(7) : s.nextDue;
        in.description = "Preventive maintenance generated from schedule \"" + s.title + "\".";
        List<Long> assignees = s.defaultAssigneeId == null ? List.of() : List.of(s.defaultAssigneeId);
        WorkOrder wo = woService.createLinked(in, assignees, createdBy);
        s.lastGeneratedAt = OffsetDateTime.now();
        schedules.save(s);
        return wo;
    }

    /**
     * Generate work orders for every schedule that is due. Each schedule is its own transaction, so one
     * conflict (e.g. the nightly worker created it a moment earlier) doesn't undo the others.
     */
    public List<WorkOrder> generateDue(Long createdBy) {
        List<WorkOrder> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Map<Long, Asset> byId = assets.findAll().stream().collect(Collectors.toMap(a -> a.id, Function.identity()));
        for (PmSchedule s : schedules.findByActiveTrue()) {
            if (!isDue(s, today, byId) || hasOpenWorkOrder(s.id)) continue;
            try {
                out.add(self.generate(s, createdBy));
            } catch (RuntimeException e) {
                log.info("Skipped PM schedule {} ({}): {}", s.id, s.title, e.getMessage());
            }
        }
        return out;
    }

    /** Built-in nightly generation (02:00 and 12:00), enabled with CMMS_PM_SCHEDULER=true. */
    @Scheduled(cron = "0 0 2,12 * * *")
    public void scheduledGeneration() {
        if (!props.pmScheduler()) return;
        List<WorkOrder> created = generateDue(null);
        log.info("Scheduled PM generation created {} work order(s)", created.size());
    }
}
