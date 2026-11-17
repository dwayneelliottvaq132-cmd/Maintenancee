package com.cmms.web;

import com.cmms.entity.Location;
import com.cmms.repo.LocationRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/locations")
public class LocationController {
    private final LocationRepository repo;

    public LocationController(LocationRepository repo) { this.repo = repo; }

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
        if (id.equals(in.parentId)) throw ApiException.bad("A location cannot be its own parent");
        in.id = id;
        return save(in);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.SUPERVISOR)
    public void delete(@PathVariable Long id) { repo.deleteById(id); }

    private Location save(Location l) {
        if (l.name == null || l.name.isBlank()) throw ApiException.bad("Name is required");
        return repo.save(l);
    }
}
