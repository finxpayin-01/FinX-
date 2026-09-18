package com.finx.payment.controller;

import com.finx.payment.service.onramp.FinancialInfrastructureReadinessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/infrastructure")
public class AdminReadinessController {

    private final FinancialInfrastructureReadinessService readinessService;

    public AdminReadinessController(FinancialInfrastructureReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> getReadiness() {
        return ResponseEntity.ok(readinessService.checkReadiness());
    }
}
