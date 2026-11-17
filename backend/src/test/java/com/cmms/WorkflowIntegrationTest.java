package com.cmms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cmms.entity.*;
import com.cmms.entity.Enums.*;
import com.cmms.repo.*;
import com.cmms.security.AuthUser;
import com.cmms.service.*;
import com.cmms.service.InstructionService.PartIn;
import com.cmms.service.InstructionService.StepIn;
import com.cmms.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Exercises the PM -> work order -> labor/parts -> complete -> sign-off flow against a real PostgreSQL
 * database (default: local "cmms_test"; override with CMMS_TEST_DB_URL / CMMS_DB_USER / CMMS_DB_PASSWORD).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${CMMS_TEST_DB_URL:jdbc:postgresql://localhost:5432/cmms_test}",
        "cmms.upload-dir=target/test-data/uploads", "cmms.report-dir=target/test-data/reports"})
class WorkflowIntegrationTest {
    @Autowired UserRepository users;
    @Autowired AssetRepository assets;
    @Autowired PartRepository parts;
    @Autowired PmScheduleRepository schedules;
    @Autowired PartService partService;
    @Autowired InstructionService instructions;
    @Autowired PmService pm;
    @Autowired WorkOrderService wos;
    @Autowired RequestService requests;

    AuthUser tech, sup, requester;
    Asset asset;
    Part filter;
    String sfx;

    User user(String name, Role role, int rate) {
        User u = new User();
        u.username = name + sfx;
        u.fullName = name + " " + sfx;
        u.role = role;
        u.passwordHash = "x";
        u.hourlyRate = BigDecimal.valueOf(rate);
        u = users.save(u);
        return u;
    }

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().substring(0, 6);
        User t = user("tech", Role.TECHNICIAN, 40), s = user("sup", Role.SUPERVISOR, 60), r = user("req", Role.REQUESTER, 0);
        tech = new AuthUser(t.id, t.username, t.role);
        sup = new AuthUser(s.id, s.username, s.role);
        requester = new AuthUser(r.id, r.username, r.role);
        asset = new Asset();
        asset.tag = "PUMP-" + sfx;
        asset.name = "Pump";
        asset = assets.save(asset);
        filter = new Part();
        filter.partNo = "F-" + sfx;
        filter.name = "Filter";
        filter.unitCost = new BigDecimal("10.00");
        filter = parts.save(filter);
        partService.receive(filter.id, new BigDecimal("5"), new BigDecimal("10.00"), "PO-1", sup.id());
    }

    @Test
    void preventiveMaintenanceLifecycle() {
        var wi = instructions.save(null, workInstruction(), List.of(new StepIn("Lock out", true), new StepIn("Swap filter", true)),
                List.of(new PartIn(filter.id, BigDecimal.ONE)));
        PmSchedule in = new PmSchedule();
        in.title = "Quarterly service";
        in.assetId = asset.id;
        in.instructionId = wi.instruction().id;
        in.frequencyType = FrequencyType.MONTHS;
        in.intervalValue = 3;
        in.nextDue = LocalDate.now();
        in.defaultAssigneeId = tech.id();
        PmSchedule sched = pm.save(null, in);

        WorkOrder wo = pm.generate(sched, sup.id());
        assertThat(wo.status).isEqualTo(WoStatus.ASSIGNED);
        assertThatThrownBy(() -> pm.generate(sched, sup.id())).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> wos.complete(wo.id, "done", null, tech)).hasMessageContaining("required instruction step");
        wos.detail(wo.id).steps().forEach(s -> wos.setStep(wo.id, s.stepId(), true, null, tech));
        wos.addLabor(wo.id, null, new BigDecimal("2"), null, null, tech);
        wos.addPart(wo.id, filter.id, new BigDecimal("2"), tech);
        assertThatThrownBy(() -> wos.addPart(wo.id, filter.id, new BigDecimal("50"), tech)).hasMessageContaining("Not enough");
        assertThatThrownBy(() -> wos.addLabor(wo.id, sup.id(), BigDecimal.ONE, null, null, tech)).hasMessageContaining("own hours");

        assertThatThrownBy(() -> wos.signOff(wo.id, "Sup", null, sup)).hasMessageContaining("must be completed");
        wos.complete(wo.id, "Replaced filter", null, tech);
        assertThatThrownBy(() -> wos.signOff(wo.id, "Tech", null, tech)).hasMessageContaining("Only supervisors");
        WorkOrder signed = wos.signOff(wo.id, "Sam Supervisor", "Looks good", sup);

        assertThat(signed.status).isEqualTo(WoStatus.SIGNED_OFF);
        var totals = wos.detail(wo.id).totals();
        assertThat(totals.labor()).isEqualByComparingTo("80");
        assertThat(totals.parts()).isEqualByComparingTo("20");
        assertThat(totals.total()).isEqualByComparingTo("100");
        assertThat(parts.findById(filter.id).orElseThrow().qtyOnHand).isEqualByComparingTo("3");
        assertThat(schedules.findById(sched.id).orElseThrow().nextDue).isEqualTo(LocalDate.now().plusMonths(3));
        assertThatThrownBy(() -> wos.addLabor(wo.id, null, BigDecimal.ONE, null, null, tech)).hasMessageContaining("no longer be changed");
    }

    @Test
    void requestApprovalCreatesWorkOrder() {
        MaintenanceRequest r = new MaintenanceRequest();
        r.title = "Pump is noisy";
        r.assetId = asset.id;
        r.priority = Priority.HIGH;
        MaintenanceRequest created = requests.create(r, requester);
        MaintenanceRequest approved = requests.approve(created.id, "ok", List.of(tech.id()), null, null, sup);
        assertThat(approved.status).isEqualTo(RequestStatus.APPROVED);
        WorkOrder wo = wos.get(approved.workOrderId);
        assertThat(wo.type).isEqualTo(WoType.REQUEST);
        assertThat(wo.priority).isEqualTo(Priority.HIGH);
        assertThat(wos.assigneeIds(wo.id)).containsExactly(tech.id());
        assertThatThrownBy(() -> requests.reject(created.id, "no", sup)).hasMessageContaining("already been approved");
    }

    @Test
    void returningPartsPutsStockBack() {
        WorkOrder in = new WorkOrder();
        in.title = "Fix leak";
        in.assetId = asset.id;
        WorkOrder wo = wos.create(in, List.of(), sup.id());
        PartUsage u = wos.addPart(wo.id, filter.id, new BigDecimal("4"), tech);
        assertThat(parts.findById(filter.id).orElseThrow().qtyOnHand).isEqualByComparingTo("1");
        wos.deletePart(wo.id, u.id, tech);
        assertThat(parts.findById(filter.id).orElseThrow().qtyOnHand).isEqualByComparingTo("5");
    }

    private WorkInstruction workInstruction() {
        WorkInstruction w = new WorkInstruction();
        w.title = "Pump service " + sfx;
        return w;
    }
}
