package com.invoice.extraction.service;

import com.invoice.extraction.entity.*;
import com.invoice.extraction.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TenantConfigService {

    private final InvoiceTenantRepository tenantRepo;
    private final TenantCityMappingRepository cityMappingRepo;

    public TenantConfigService(InvoiceTenantRepository tenantRepo,
                               TenantCityMappingRepository cityMappingRepo) {
        this.tenantRepo = tenantRepo;
        this.cityMappingRepo = cityMappingRepo;
    }

    // Cache this — it only changes when someone edits the DB via admin API
    @Cacheable(value = "activeTenants")
    public List<InvoiceTenant> getAllActiveTenants() {
        List<InvoiceTenant> tenants = tenantRepo.findAllActiveWithConfig();
        log.info("Loaded {} active tenant configs", tenants.size());
        return tenants;
    }

    public Map<String, String> getCityMappings(Long tenantId) {
        return cityMappingRepo
                .findByTenantIdAndMappingType(tenantId, "CITY")
                .stream()
                .collect(Collectors.toMap(
                        m -> m.getCode().toUpperCase(),
                        TenantCityMapping::getDisplayName
                ));
    }

    // Call this after any DB change to the tenant tables
    public void invalidateCache() {
        log.info("Invalidating tenant config cache");
        // If using Spring Cache with a CacheManager, evict here:
        // cacheManager.getCache("activeTenants").clear();
    }
}