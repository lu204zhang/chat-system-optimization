package org.example.chatflow.consumer.cache;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoint for inspecting and managing the caching layer.
 * Useful for verifying that caching is working during testing.
 */
@RestController
@RequestMapping("/api/cache")
public class CacheController {

    private final CacheService cacheService;

    public CacheController(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * Returns cache statistics: hit/miss rates, entry counts, invalidation totals.
     * <pre>GET /api/cache/stats</pre>
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return cacheService.getCacheStats();
    }
}
