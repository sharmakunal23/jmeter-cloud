package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.region.RegionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/v1/regions/status} — every configured region with, for the
 * routed ones, the last probe's verdict. The UI's Home checklist reads it.
 */
@RestController
@RequestMapping("/api/v1/regions")
public class RegionStatusController {

    private final RegionRegistry regions;

    public RegionStatusController(RegionRegistry regions) {
        this.regions = regions;
    }

    @GetMapping("/status")
    public List<RegionStatus> status() {
        return regions.all();
    }
}
