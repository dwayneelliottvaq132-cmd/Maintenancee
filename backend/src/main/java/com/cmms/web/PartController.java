package com.cmms.web;

import com.cmms.entity.Part;
import com.cmms.entity.StockTransaction;
import com.cmms.repo.PartRepository;
import com.cmms.repo.StockTransactionRepository;
import com.cmms.security.Current;
import com.cmms.service.AuditService;
import com.cmms.service.PartService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/parts")
public class PartController {
    public record ReceiveIn(BigDecimal qty, BigDecimal unitCost, String reference) {}
    public record AdjustIn(BigDecimal countedQty, String reason) {}

    private final PartRepository repo;
    private final StockTransactionRepository txs;
    private final PartService service;
    private final AuditService audit;

    public PartController(PartRepository repo, StockTransactionRepository txs, PartService service, AuditService audit) {
        this.repo = repo;
        this.txs = txs;
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    public List<Part> list(@RequestParam(defaultValue = "false") boolean lowStock) {
        List<Part> all = repo.findAll(Sort.by("partNo"));
        if (!lowStock) return all;
        return all.stream().filter(p -> p.active && p.qtyOnHand.compareTo(p.reorderPoint) <= 0).toList();
    }

    @GetMapping("/{id}")
    public Part get(@PathVariable Long id) { return service.get(id); }

    @GetMapping("/{id}/transactions")
    public List<StockTransaction> transactions(@PathVariable Long id) { return txs.findByPartIdOrderByCreatedAtDesc(id); }

    @PostMapping
    @PreAuthorize(Roles.SUPERVISOR)
    public Part create(@RequestBody Part in) {
        in.id = null;
        BigDecimal opening = in.qtyOnHand == null ? BigDecimal.ZERO : in.qtyOnHand;
        in.qtyOnHand = BigDecimal.ZERO;
        Part p = save(in);
        if (opening.signum() > 0) p = service.receive(p.id, opening, p.unitCost, "Opening balance", Current.user().id());
        audit.log("part", p.id, "CREATE", p.partNo);
        return p;
    }

    /** Edits part details; quantity changes go through receive/adjust so they are traceable. */
    @PutMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public Part update(@PathVariable Long id, @RequestBody Part in) {
        Part p = service.get(id);
        in.id = id;
        in.qtyOnHand = p.qtyOnHand;
        return save(in);
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize(Roles.STAFF)
    public Part receive(@PathVariable Long id, @RequestBody ReceiveIn in) {
        return service.receive(id, in.qty(), in.unitCost(), in.reference(), Current.user().id());
    }

    @PostMapping("/{id}/adjust")
    @PreAuthorize(Roles.SUPERVISOR)
    public Part adjust(@PathVariable Long id, @RequestBody AdjustIn in) {
        return service.adjust(id, in.countedQty(), in.reason() == null ? "Stock count" : in.reason(), Current.user().id());
    }

    private Part save(Part p) {
        if (p.partNo == null || p.partNo.isBlank()) throw ApiException.bad("Part number is required");
        if (p.name == null || p.name.isBlank()) throw ApiException.bad("Name is required");
        if (p.unitCost == null) p.unitCost = BigDecimal.ZERO;
        if (p.reorderPoint == null) p.reorderPoint = BigDecimal.ZERO;
        if (p.unit == null || p.unit.isBlank()) p.unit = "ea";
        return repo.save(p);
    }
}
