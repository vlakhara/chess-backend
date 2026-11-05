package com.vlakhara.chess;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Lightweight health/keep-alive endpoint. An external scheduler can call it
 * periodically so a free-tier host does not put the service to sleep.
 */
@RestController
public class PingController {

    @GetMapping(value = "/ping", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ping() {
        return Map.of("status", "ok", "timestamp", Instant.now().toString());
    }
}
