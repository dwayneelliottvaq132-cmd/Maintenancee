package com.cmms.web;

import com.cmms.entity.Location;
import com.cmms.repo.LocationRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/locations")
public class LocationController {
    private final LocationRepository repo;
    private final JdbcTemplate jdbc;

    public LocationController(LocationRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Location> list() { return repo.findAll(Sort.by("name")); }

    @PostMapping
    @PreAuthorize(Roles.SUPERVISOR)
    public Location create(@RequestBody Location in) {
        in.id = null;
        return save(in);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public Location update(@PathVariable Long id, @RequestBody Location in) {
        repo.findById(id).orElseThrow(() -> ApiException.notFound("Location", id));
        in.id = id;
        return save(in);
    }

    /** Only unused locations can be deleted (assets, work orders, requests and sub-locations keep their place). */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public void delete(@PathVariable Long id) {
        repo.findById(id).orElseThrow(() -> ApiException.notFound("Location", id));
        Integer refs = jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) FROM assets WHERE location_id = ?) + (SELECT COUNT(*) FROM work_orders WHERE location_id = ?)
                 + (SELECT COUNT(*) FROM maintenance_requests WHERE location_id = ?) + (SELECT COUNT(*) FROM locations WHERE parent_id = ?)""",
                Integer.class, id, id, id, id);
        if (refs != null && refs > 0) {
            throw ApiException.conflict("This location is still used by assets, work orders, requests or other locations, so it can't be deleted");
        }
        repo.deleteById(id);
    }

    private Location save(Location l) {
        if (l.name == null || l.name.isBlank()) throw ApiException.bad("Name is required");
        l.name = l.name.trim();
        // no loops: a location can't be inside itself, directly or through other locations
        Long parent = l.parentId;
        Set<Long> seen = new HashSet<>();
        while (parent != null) {
            if (parent.equals(l.id)) throw ApiException.bad("A location cannot be inside itself");
            if (!seen.add(parent)) break;
            parent = repo.findById(parent).map(p -> p.parentId).orElse(null);
        }
        return repo.save(l);
    }
}
