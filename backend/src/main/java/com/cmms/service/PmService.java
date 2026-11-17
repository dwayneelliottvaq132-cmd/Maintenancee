package com.cmms.service;

import com.cmms.entity.*;
import com.cmms.entity.Enums.*;
import com.cmms.repo.*;
import com.cmms.web.ApiException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PM schedule maintenance and work-order generation. The nightly Python worker
 * (worker/cmms_worker/pm.py) applies the same rules; "Generate now" in the UI uses this class.
 */
@Service
public class PmService {
    private final PmScheduleRepository schedules;
    private final WorkOrderRepository wos;
    private final AssetRepository assets;
    private final WorkOrderService woService;

    public PmService(PmScheduleRepository schedules, WorkOrderRepository wos, AssetRepository assets, WorkOrderService woService) {
        this.schedules = schedules;
        this.wos = wos;
        this.assets = assets;
        this.woService = woService;
    }

    public PmSchedule get(Long id) {
        return schedules.findById(id).orElseThrow(() -> ApiException.notFound("PM schedule", id));
    }

    @Transactional
    public PmSchedule save(Long id, PmSchedule in) {
        if (in.title == null || in.title.isBlank()) throw ApiException.bad("Title is required");
        if (in.assetId == null) throw ApiException.bad("Asset is required");
        if (in.frequencyType == null || in.intervalValue <= 0) throw ApiException.bad("Frequency and a positive interval are required");
        PmSchedule s = id == null ? new PmSchedule() : get(id);
        s.title = in.title;
        s.assetId = in.assetId;
        s.instructionId = in.instructionId;
        s.frequencyType = in.frequencyType;
        s.intervalValue = in.intervalValue;
        s.leadDays = Math.max(0, in.leadDays);
        s.priority = in.priority == null ? Priority.MEDIUM : in.priority;
        s.defaultAssigneeId = in.defaultAssigneeId;
        s.active = in.active;
        if (s.frequencyType == FrequencyType.METER) {
            s.nextDue = null;
            if (in.nextMeter == null) {
                Asset a = assets.findById(in.assetId).orElseThrow(() -> ApiException.notFound("Asset", in.assetId));
                s.nextMeter = a.meterReading.add(java.math.BigDecimal.valueOf(in.intervalValue));
            } else s.nextMeter = in.nextMeter;
        } else {
            s.nextMeter = null;
            s.nextDue = in.nextDue != null ? in.nextDue : LocalDate.now();
        }
        return schedules.save(s);
    }

    public boolean isDue(PmSchedule s, LocalDate today) {
        if (!s.active) return false;
        if (s.frequencyType == FrequencyType.METER) {
            if (s.nextMeter == null) return false;
            return assets.findById(s.assetId).map(a -> a.meterReading.compareTo(s.nextMeter) >= 0).orElse(false);
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
        WorkOrder wo = woService.create(in, assignees, createdBy);
        s.lastGeneratedAt = OffsetDateTime.now();
        schedules.save(s);
        return wo;
    }

    /** Generate work orders for every schedule that is due today. */
    @Transactional
    public List<WorkOrder> generateDue(Long createdBy) {
        List<WorkOrder> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (PmSchedule s : schedules.findByActiveTrue()) {
            if (isDue(s, today) && !hasOpenWorkOrder(s.id)) out.add(generate(s, createdBy));
        }
        return out;
    }
}
