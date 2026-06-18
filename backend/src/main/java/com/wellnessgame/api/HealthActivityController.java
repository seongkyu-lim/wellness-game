package com.wellnessgame.api;

import com.wellnessgame.activity.HealthActivitySyncService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health-activities")
public class HealthActivityController {
    private final HealthActivitySyncService syncService;

    public HealthActivityController(HealthActivitySyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/sync")
    public ResponseEntity<HealthActivitySyncResponse> sync(@Valid @RequestBody HealthActivitySyncRequest request) {
        return ResponseEntity.ok(syncService.sync(request));
    }
}

