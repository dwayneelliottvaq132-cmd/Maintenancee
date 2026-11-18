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
    @Autowired LaborEntryRepository labor;

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
        var wi = instructions.save(null, workInstruction(), List.of(new StepIn(null, "Lock out", true), new StepIn(null, "Swap filter", true)),
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

    // ---------------- regression tests for the code-review findings ----------------

    private WorkOrder newWo(String title) {
        WorkOrder in = new WorkOrder();
        in.title = title;
        in.assetId = asset.id;
        return wos.create(in, List.of(tech.id()), sup.id());
    }

    @Test
    void lastPartCannotBeIssuedTwiceConcurrently() throws Exception {
        partService.adjust(filter.id, BigDecimal.ONE, "test", sup.id());
        WorkOrder a = newWo("A"), b = newWo("B");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> ta = () -> { start.await(); try { wos.addPart(a.id, filter.id, BigDecimal.ONE, tech); return true; } catch (ApiException e) { return false; } };
        java.util.concurrent.Callable<Boolean> tb = () -> { start.await(); try { wos.addPart(b.id, filter.id, BigDecimal.ONE, tech); return true; } catch (ApiException e) { return false; } };
        var fa = pool.submit(ta); var fb = pool.submit(tb);
        start.countDown();
        int ok = (fa.get() ? 1 : 0) + (fb.get() ? 1 : 0);
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(parts.findById(filter.id).orElseThrow().qtyOnHand).isEqualByComparingTo("0");
    }

    @Test
    void completedWorkIsFrozenUntilSentBack() {
        var wi = instructions.save(null, workInstruction(), List.of(new StepIn(null, "Check", true)), List.of());
        WorkOrder in = new WorkOrder();
        in.title = "Frozen";
        in.instructionId = wi.instruction().id;
        WorkOrder wo = wos.create(in, List.of(tech.id()), sup.id());
        Long stepId = wos.detail(wo.id).steps().get(0).stepId();
        wos.setStep(wo.id, stepId, true, null, tech);
        wos.complete(wo.id, "done", null, tech);
        assertThatThrownBy(() -> wos.setStep(wo.id, stepId, false, null, tech)).hasMessageContaining("send it back");
        assertThatThrownBy(() -> wos.addLabor(wo.id, null, BigDecimal.ONE, null, null, tech)).hasMessageContaining("send it back");
        wos.reopen(wo.id, "redo");
        wos.setStep(wo.id, stepId, false, null, tech);   // allowed again after send-back
        assertThatThrownBy(() -> wos.complete(wo.id, "done", null, tech)).hasMessageContaining("required instruction step");
    }

    @Test
    void editingInstructionKeepsCheckOffHistory() {
        var wi = instructions.save(null, workInstruction(), List.of(new StepIn(null, "A", true), new StepIn(null, "B", true)), List.of());
        Long iid = wi.instruction().id;
        Long a = wi.steps().get(0).id, b = wi.steps().get(1).id;
        WorkOrder in = new WorkOrder();
        in.title = "History";
        in.instructionId = iid;
        WorkOrder wo = wos.create(in, List.of(tech.id()), sup.id());
        wos.setStep(wo.id, b, true, null, tech);
        // insert X between A and B, and reword A (A has no results, so it's simply updated)
        var edited = instructions.save(iid, workInstruction(), List.of(new StepIn(a, "A2", true), new StepIn(null, "X", true), new StepIn(b, "B", true)), List.of());
        assertThat(edited.steps()).extracting(s -> s.text).containsExactly("A2", "X", "B");
        var steps = wos.detail(wo.id).steps();
        assertThat(steps).filteredOn(s -> s.done()).extracting(s -> s.text()).containsExactly("B");
        // reword B after it was checked: the old wording stays on this job, a new step replaces it for new jobs
        instructions.save(iid, workInstruction(), List.of(new StepIn(a, "A2", true), new StepIn(b, "B reworded", true)), List.of());
        assertThat(wos.detail(wo.id).steps()).filteredOn(s -> s.done()).extracting(s -> s.text()).containsExactly("B");
        assertThat(instructions.get(iid).steps()).extracting(s -> s.text).containsExactly("A2", "B reworded");
        assertThatThrownBy(() -> instructions.delete(iid)).hasMessageContaining("used by work orders");
    }

    @Test
    void cancellingPmWorkOrderSkipsThatOccurrence() {
        PmSchedule in = new PmSchedule();
        in.title = "Weekly";
        in.assetId = asset.id;
        in.frequencyType = FrequencyType.WEEKS;
        in.intervalValue = 1;
        in.nextDue = LocalDate.now().minusDays(2);
        PmSchedule sched = pm.save(null, in);
        WorkOrder wo = pm.generate(sched, sup.id());
        wos.cancel(wo.id, "not needed");
        PmSchedule after = schedules.findById(sched.id).orElseThrow();
        assertThat(after.nextDue).isEqualTo(LocalDate.now().plusWeeks(1));
        assertThat(pm.generateDue(sup.id())).noneMatch(w -> sched.id.equals(w.pmScheduleId));
    }

    @Test
    void publicCreateCannotLinkToScheduleOrRequest() {
        WorkOrder in = new WorkOrder();
        in.title = "Sneaky";
        in.type = WoType.REQUEST;
        in.pmScheduleId = 999999L;
        in.requestId = 999999L;
        WorkOrder wo = wos.create(in, List.of(), tech.id());
        assertThat(wo.pmScheduleId).isNull();
        assertThat(wo.requestId).isNull();
        assertThat(wo.type).isEqualTo(WoType.CORRECTIVE);
    }

    @Test
    void partialUpdateKeepsFieldsThatWereNotSent() {
        WorkOrder wo = newWo("Keep my asset");
        wos.update(wo.id, java.util.Map.of("title", "Renamed"));
        WorkOrder after = wos.get(wo.id);
        assertThat(after.title).isEqualTo("Renamed");
        assertThat(after.assetId).isEqualTo(asset.id);
    }

    @Test
    void techniciansDoNotSeePayRates() {
        WorkOrder wo = newWo("Pay");
        wos.addLabor(wo.id, null, new BigDecimal("2"), null, null, tech);
        var forTech = wos.detail(wo.id, false);
        assertThat(forTech.labor()).allMatch(l -> l.hourlyRate == null);
        assertThat(forTech.totals().labor()).isNull();
        assertThat(wos.detail(wo.id, true).totals().labor()).isEqualByComparingTo("80");
        assertThat(labor.findByWorkOrderId(wo.id)).allMatch(l -> l.hourlyRate != null);  // nothing was overwritten
    }

    private WorkInstruction workInstruction() {
        WorkInstruction w = new WorkInstruction();
        w.title = "Pump service " + sfx;
        return w;
    }
}
