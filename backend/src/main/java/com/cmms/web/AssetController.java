package com.cmms.web;

import com.cmms.config.CmmsProperties;
import com.cmms.entity.Asset;
import com.cmms.entity.AssetDocument;
import com.cmms.entity.Enums.AssetStatus;
import com.cmms.entity.Enums.Level;
import com.cmms.repo.AssetDocumentRepository;
import com.cmms.repo.AssetRepository;
import com.cmms.repo.PmScheduleRepository;
import com.cmms.repo.WorkOrderRepository;
import com.cmms.security.Current;
import com.cmms.service.AuditService;
import com.cmms.service.WorkOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
    public record MeterIn(BigDecimal reading) {}

    /** Only these types may be shown in the browser; everything else is a plain download. */
    static final Map<String, MediaType> INLINE_TYPES = Map.of(
            "pdf", MediaType.APPLICATION_PDF, "png", MediaType.IMAGE_PNG, "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG, "gif", MediaType.IMAGE_GIF, "txt", MediaType.TEXT_PLAIN);

    private final AssetRepository repo;
    private final AssetDocumentRepository docs;
    private final PmScheduleRepository schedules;
    private final WorkOrderRepository workOrders;
    private final WorkOrderService woService;
    private final JdbcTemplate jdbc;
    private final CmmsProperties props;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public AssetController(AssetRepository repo, AssetDocumentRepository docs, PmScheduleRepository schedules,
                           WorkOrderRepository workOrders, WorkOrderService woService, JdbcTemplate jdbc,
                           CmmsProperties props, AuditService audit, ObjectMapper mapper) {
        this.repo = repo;
        this.docs = docs;
        this.schedules = schedules;
        this.workOrders = workOrders;
        this.woService = woService;
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.mapper = mapper;
    }

    /** Everyone can list assets (requesters pick one when reporting a problem); costs only for supervisors. */
    @GetMapping
    public List<Map<String, Object>> list() {
        boolean showCost = Current.user().isSupervisor();
        return jdbc.queryForList("""
            WITH open_wo AS (SELECT asset_id, COUNT(*) AS n FROM work_orders
                              WHERE status NOT IN ('SIGNED_OFF','CANCELLED') AND asset_id IS NOT NULL GROUP BY asset_id),
                 cost AS (SELECT asset_id, SUM(amount) AS total FROM v_cost_lines WHERE asset_id IS NOT NULL GROUP BY asset_id),
                 pm AS (SELECT asset_id, MIN(next_due) AS next_due FROM pm_schedules WHERE active GROUP BY asset_id)
            SELECT a.*, l.name AS location_name, COALESCE(open_wo.n, 0) AS open_work_orders,
                   COALESCE(cost.total, 0) AS lifetime_cost, pm.next_due AS next_pm_due
              FROM assets a LEFT JOIN locations l ON l.id = a.location_id
              LEFT JOIN open_wo ON open_wo.asset_id = a.id LEFT JOIN cost ON cost.asset_id = a.id
              LEFT JOIN pm ON pm.asset_id = a.id
             ORDER BY a.tag""").stream().map(AssetController::camel).peek(m -> {
            if (!showCost) { m.remove("lifetimeCost"); m.remove("purchaseCost"); }
        }).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.STAFF)
    public Map<String, Object> get(@PathVariable Long id) {
        Asset a = find(id);
        boolean showCost = Current.user().isSupervisor();
        Map<String, Object> out = new LinkedHashMap<>();
        if (!showCost) a.purchaseCost = null;
        out.put("asset", a);
        out.put("schedules", schedules.findByAssetId(id));
        List<Map<String, Object>> wos = woService.list(null, id, null, null, false);
        if (!showCost) wos.forEach(w -> w.remove("totalCost"));
        out.put("workOrders", wos);
        out.put("documents", docs.findByAssetIdOrderByUploadedAtDesc(id));
        out.put("children", jdbc.queryForList("SELECT id, tag, name, status FROM assets WHERE parent_asset_id = ? ORDER BY tag", id));
        if (showCost) out.put("costs", costs(id));
        return out;
    }

    /** Spending on this asset broken down by cost type and by year. */
    @GetMapping("/{id}/costs")
    @PreAuthorize(Roles.SUPERVISOR)
    public Map<String, Object> costs(@PathVariable Long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byType", jdbc.queryForList("SELECT cost_type AS \"type\", SUM(amount) AS amount, SUM(hours) AS hours FROM v_cost_lines WHERE asset_id = ? GROUP BY cost_type", id));
        out.put("byYear", jdbc.queryForList("SELECT EXTRACT(YEAR FROM cost_date)::int AS year, SUM(amount) AS amount FROM v_cost_lines WHERE asset_id = ? GROUP BY 1 ORDER BY 1", id));
        out.put("total", jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM v_cost_lines WHERE asset_id = ?", BigDecimal.class, id));
        return out;
    }

    @PostMapping
    @PreAuthorize(Roles.SUPERVISOR)
    public Asset create(@RequestBody Asset in) {
        in.id = null;
        in.createdAt = null;
        if (in.meterReading == null) in.meterReading = BigDecimal.ZERO;
        Asset a = save(in);
        audit.log("asset", a.id, "CREATE", a.tag);
        return a;
    }

    /** Partial update: fields left out of the request keep their current value. */
    @PutMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public Asset update(@PathVariable Long id, @RequestBody Map<String, Object> changes) {
        Asset a = find(id);
        changes.keySet().removeAll(Set.of("id", "createdAt", "locationName", "openWorkOrders", "lifetimeCost", "nextPmDue"));
        try {
            mapper.updateValue(a, changes);
        } catch (Exception e) {
            throw ApiException.bad("Invalid value: " + e.getMessage());
        }
        audit.log("asset", id, "UPDATE", a.tag);
        return save(a);
    }

    /** Record a meter reading (hours, miles, cycles). Only supervisors may lower one (e.g. to fix a typo). */
    @PostMapping("/{id}/meter")
    @PreAuthorize(Roles.STAFF)
    public Asset meter(@PathVariable Long id, @RequestBody MeterIn in) {
        Asset a = find(id);
        if (in.reading() == null || in.reading().signum() < 0) throw ApiException.bad("Reading must be zero or more");
        if (in.reading().compareTo(a.meterReading) < 0 && !Current.user().isSupervisor()) {
            throw ApiException.bad("The new reading is lower than the current one (" + a.meterReading.stripTrailingZeros().toPlainString()
                    + "). Ask a supervisor to correct it");
        }
        a.meterReading = in.reading();
        audit.log("asset", id, "METER", in.reading().toPlainString());
        return repo.save(a);
    }

    /** Assets with work history can't be deleted (their costs would vanish from reports); retire them instead. */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    @Transactional
    public void delete(@PathVariable Long id) throws IOException {
        Asset a = find(id);
        if (workOrders.existsByAssetId(id)) {
            throw ApiException.conflict("This asset has work orders. Set its status to Retired instead of deleting it");
        }
        Integer refs = jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) FROM maintenance_requests WHERE asset_id = ?) + (SELECT COUNT(*) FROM assets WHERE parent_asset_id = ?)""",
                Integer.class, id, id);
        if (refs != null && refs > 0) throw ApiException.conflict("This asset is referenced by requests or other assets. Retire it instead");
        for (AssetDocument d : docs.findByAssetIdOrderByUploadedAtDesc(id)) Files.deleteIfExists(Path.of(d.storedPath));
        repo.delete(a);
        audit.log("asset", id, "DELETE", a.tag);
    }

    @PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.STAFF)
    public AssetDocument upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        find(id);
        if (file.isEmpty()) throw ApiException.bad("File is empty");
        String original = Paths.get(Objects.requireNonNullElse(file.getOriginalFilename(), "file")).getFileName().toString();
        Path dir = Path.of(props.uploadDir(), "assets", String.valueOf(id)).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + "-" + original.replaceAll("[^A-Za-z0-9._-]", "_")).normalize();
        if (!target.startsWith(dir)) throw ApiException.bad("Bad file name");
        file.transferTo(target);
        AssetDocument d = new AssetDocument();
        d.assetId = id;
        d.fileName = original;
        d.storedPath = target.toString();
        d.contentType = typeFor(original).toString();   // never trust the browser-supplied type
        d.sizeBytes = file.getSize();
        d.uploadedBy = Current.user().id();
        return docs.save(d);
    }

    /**
     * PDFs, images and text open in the browser; anything else (including HTML/SVG) is always downloaded
     * as a file, so an uploaded document can never run as a web page inside the app.
     */
    @GetMapping("/{id}/documents/{docId}")
    @PreAuthorize(Roles.STAFF)
    public ResponseEntity<Resource> download(@PathVariable Long id, @PathVariable Long docId) {
        AssetDocument d = docs.findById(docId).filter(x -> x.assetId.equals(id))
                .orElseThrow(() -> ApiException.notFound("Document", docId));
        Resource r = new FileSystemResource(d.storedPath);
        if (!r.exists()) throw ApiException.notFound("File for document", docId);
        MediaType type = typeFor(d.fileName);
        boolean inline = !type.equals(MediaType.APPLICATION_OCTET_STREAM);
        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(d.fileName, java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(r);
    }

    static MediaType typeFor(String fileName) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        return INLINE_TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM);
    }

    @DeleteMapping("/{id}/documents/{docId}")
    @PreAuthorize(Roles.SUPERVISOR)
    public void deleteDoc(@PathVariable Long id, @PathVariable Long docId) throws IOException {
        AssetDocument d = docs.findById(docId).filter(x -> x.assetId.equals(id))
                .orElseThrow(() -> ApiException.notFound("Document", docId));
        Files.deleteIfExists(Path.of(d.storedPath));
        docs.delete(d);
    }

    private Asset find(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Asset", id));
    }

    private Asset save(Asset a) {
        if (a.tag == null || a.tag.isBlank()) throw ApiException.bad("Asset tag is required");
        if (a.name == null || a.name.isBlank()) throw ApiException.bad("Name is required");
        if (a.purchaseCost != null && a.purchaseCost.signum() < 0) throw ApiException.bad("Purchase cost cannot be negative");
        if (a.meterReading == null) a.meterReading = BigDecimal.ZERO;
        if (a.meterReading.signum() < 0) throw ApiException.bad("Meter reading cannot be negative");
        if (a.status == null) a.status = AssetStatus.ACTIVE;
        if (a.criticality == null) a.criticality = Level.MEDIUM;
        checkNoCycle(a);
        a.tag = a.tag.trim();
        return repo.save(a);
    }

    /** An asset can't be (directly or indirectly) a component of itself. */
    private void checkNoCycle(Asset a) {
        Long parent = a.parentAssetId;
        Set<Long> seen = new HashSet<>();
        while (parent != null) {
            if (parent.equals(a.id)) throw ApiException.bad("An asset cannot be part of itself");
            if (!seen.add(parent)) break;
            parent = repo.findById(parent).map(p -> p.parentAssetId).orElse(null);
        }
    }

    /** snake_case row keys -> camelCase JSON keys */
    static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            StringBuilder sb = new StringBuilder();
            boolean up = false;
            for (char c : k.toCharArray()) {
                if (c == '_') up = true;
                else { sb.append(up ? Character.toUpperCase(c) : c); up = false; }
            }
            out.put(sb.toString(), v);
        });
        return out;
    }
}
