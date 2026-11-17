package com.cmms.web;

import com.cmms.config.CmmsProperties;
import com.cmms.entity.Asset;
import com.cmms.entity.AssetDocument;
import com.cmms.repo.AssetDocumentRepository;
import com.cmms.repo.AssetRepository;
import com.cmms.repo.PmScheduleRepository;
import com.cmms.security.Current;
import com.cmms.service.AuditService;
import com.cmms.service.WorkOrderService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
    public record MeterIn(BigDecimal reading) {}

    private final AssetRepository repo;
    private final AssetDocumentRepository docs;
    private final PmScheduleRepository schedules;
    private final WorkOrderService woService;
    private final JdbcTemplate jdbc;
    private final CmmsProperties props;
    private final AuditService audit;

    public AssetController(AssetRepository repo, AssetDocumentRepository docs, PmScheduleRepository schedules,
                           WorkOrderService woService, JdbcTemplate jdbc, CmmsProperties props, AuditService audit) {
        this.repo = repo;
        this.docs = docs;
        this.schedules = schedules;
        this.woService = woService;
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
            SELECT a.*, l.name AS location_name,
                   (SELECT COUNT(*) FROM work_orders w WHERE w.asset_id = a.id AND w.status NOT IN ('SIGNED_OFF','CANCELLED')) AS open_work_orders,
                   COALESCE((SELECT SUM(amount) FROM v_cost_lines c WHERE c.asset_id = a.id), 0) AS lifetime_cost,
                   (SELECT MIN(next_due) FROM pm_schedules p WHERE p.asset_id = a.id AND p.active) AS next_pm_due
              FROM assets a LEFT JOIN locations l ON l.id = a.location_id
             ORDER BY a.tag""").stream().map(AssetController::camel).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Asset a = find(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asset", a);
        out.put("schedules", schedules.findByAssetId(id));
        out.put("workOrders", woService.list(null, id, null, null, false));
        out.put("documents", docs.findByAssetIdOrderByUploadedAtDesc(id));
        out.put("children", jdbc.queryForList("SELECT id, tag, name, status FROM assets WHERE parent_asset_id = ? ORDER BY tag", id));
        out.put("costs", costs(id));
        return out;
    }

    /** Spending on this asset broken down by cost type and by year. */
    @GetMapping("/{id}/costs")
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
        Asset a = save(in);
        audit.log("asset", a.id, "CREATE", a.tag);
        return a;
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public Asset update(@PathVariable Long id, @RequestBody Asset in) {
        Asset existing = find(id);
        if (id.equals(in.parentAssetId)) throw ApiException.bad("An asset cannot be its own parent");
        in.id = id;
        in.createdAt = existing.createdAt;
        if (in.meterReading == null) in.meterReading = existing.meterReading;
        audit.log("asset", id, "UPDATE", in.tag);
        return save(in);
    }

    /** Technicians record meter readings (hours, miles, cycles) for meter-based PM. */
    @PostMapping("/{id}/meter")
    @PreAuthorize(Roles.STAFF)
    public Asset meter(@PathVariable Long id, @RequestBody MeterIn in) {
        Asset a = find(id);
        if (in.reading() == null || in.reading().signum() < 0) throw ApiException.bad("Reading must be zero or more");
        a.meterReading = in.reading();
        audit.log("asset", id, "METER", in.reading().toPlainString());
        return repo.save(a);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    public void delete(@PathVariable Long id) {
        find(id);
        repo.deleteById(id);
        audit.log("asset", id, "DELETE", null);
    }

    @PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.STAFF)
    public AssetDocument upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        find(id);
        if (file.isEmpty()) throw ApiException.bad("File is empty");
        String original = Paths.get(Objects.requireNonNullElse(file.getOriginalFilename(), "file")).getFileName().toString();
        Path dir = Path.of(props.uploadDir(), "assets", String.valueOf(id));
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + "-" + original.replaceAll("[^A-Za-z0-9._-]", "_"));
        file.transferTo(target.toAbsolutePath());
        AssetDocument d = new AssetDocument();
        d.assetId = id;
        d.fileName = original;
        d.storedPath = target.toString();
        d.contentType = file.getContentType();
        d.sizeBytes = file.getSize();
        d.uploadedBy = Current.user().id();
        return docs.save(d);
    }

    @GetMapping("/{id}/documents/{docId}")
    public ResponseEntity<Resource> download(@PathVariable Long id, @PathVariable Long docId) {
        AssetDocument d = docs.findById(docId).filter(x -> x.assetId.equals(id))
                .orElseThrow(() -> ApiException.notFound("Document", docId));
        Resource r = new FileSystemResource(d.storedPath);
        if (!r.exists()) throw ApiException.notFound("File for document", docId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.contentType == null ? "application/octet-stream" : d.contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(d.fileName).build().toString())
                .body(r);
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
        if (a.status == null) a.status = com.cmms.entity.Enums.AssetStatus.ACTIVE;
        if (a.criticality == null) a.criticality = com.cmms.entity.Enums.Level.MEDIUM;
        if (a.meterReading == null) a.meterReading = BigDecimal.ZERO;
        return repo.save(a);
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
