package com.invoice.extraction.service;

import com.invoice.extraction.entity.InvoiceTenant;
import com.invoice.extraction.entity.TenantIdentifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Slf4j
public class TenantIdentificationService {

    private final TenantConfigService configService;

    public TenantIdentificationService(TenantConfigService configService) {
        this.configService = configService;
    }

    /**
     * Scores all active tenants against the extracted PDF text.
     * Returns the tenant with the highest score, or empty if none match.
     */
    public Optional<InvoiceTenant> identifyTenant(String pdfText) {
        List<InvoiceTenant> tenants = configService.getAllActiveTenants();

        InvoiceTenant bestMatch = null;
        int bestScore = 0;

        for (InvoiceTenant tenant : tenants) {
            int score = scoreTenant(pdfText, tenant);
            log.debug("Tenant '{}' scored {} points", tenant.getTenantKey(), score);

            if (score > bestScore) {
                bestScore = score;
                bestMatch = tenant;
            }
        }

        if (bestMatch != null) {
            log.info("Identified tenant: {} (score: {})", bestMatch.getTenantKey(), bestScore);
        } else {
            log.warn("No tenant matched the provided PDF");
        }

        return Optional.ofNullable(bestMatch);
    }

    private int scoreTenant(String text, InvoiceTenant tenant) {
        int totalScore = 0;

        for (TenantIdentifier identifier : tenant.getIdentifiers()) {
            try {
                Pattern p = Pattern.compile(identifier.getPattern(), Pattern.CASE_INSENSITIVE);
                if (p.matcher(text).find()) {
                    totalScore += identifier.getScore();
                }
            } catch (Exception e) {
                log.warn("Invalid identifier pattern for tenant {}: {}",
                        tenant.getTenantKey(), identifier.getPattern());
            }
        }

        return totalScore;
    }
}