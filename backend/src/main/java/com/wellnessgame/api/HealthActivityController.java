package com.wellnessgame.api;

import com.wellnessgame.activity.HealthActivity;
import com.wellnessgame.activity.HealthActivityRepository;
import com.wellnessgame.activity.HealthActivitySyncService;
import com.wellnessgame.api.DailyActivitiesResponse.ActivityEntry;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/health-activities")
public class HealthActivityController {
    private final HealthActivitySyncService syncService;
    private final HealthActivityRepository activityRepository;

    public HealthActivityController(HealthActivitySyncService syncService, HealthActivityRepository activityRepository) {
        this.syncService = syncService;
        this.activityRepository = activityRepository;
    }

    @PostMapping("/sync")
    public ResponseEntity<HealthActivitySyncResponse> sync(@Valid @RequestBody HealthActivitySyncRequest request) {
        return ResponseEntity.ok(syncService.sync(request));
    }

    @GetMapping
    public DailyActivitiesResponse daily(
            @RequestParam String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        List<ActivityEntry> activities = activityRepository.findByUserIdAndActivityDate(userId, date).stream()
                .sorted(Comparator.comparing(HealthActivity::getStartedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ActivityEntry::from)
                .toList();
        return new DailyActivitiesResponse(userId, date, activities);
    }
}
