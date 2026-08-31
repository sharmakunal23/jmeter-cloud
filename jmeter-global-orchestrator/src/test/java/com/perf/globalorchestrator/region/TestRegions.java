package com.perf.globalorchestrator.region;

import com.perf.globalorchestrator.domain.Region;
import com.perf.globalorchestrator.repo.RegionRepository;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture: a {@link RegionRegistry} over a mocked {@link RegionRepository}
 * seeded with {@code id=url} entries — the runtime cluster registry without a
 * database. Every entry is a registered (routed) cluster; an id not listed is
 * simply unregistered, which is how "cannot provision there" is modelled now.
 */
public final class TestRegions {

    private TestRegions() {}

    public static RegionRegistry registryOf(String... entries) {
        List<Region> rows = new ArrayList<>();
        for (String e : entries) {
            int i = e.indexOf('=');
            if (i < 1) throw new IllegalArgumentException("expected id=url, got: " + e);
            String id = e.substring(0, i);
            rows.add(new Region(id, id, e.substring(i + 1), 20,
                    Instant.now(), null, null, null, Instant.now(), Instant.now()));
        }
        RegionRepository repo = Mockito.mock(RegionRepository.class);
        Mockito.when(repo.findAll()).thenReturn(rows);
        return new RegionRegistry(repo);
    }
}
