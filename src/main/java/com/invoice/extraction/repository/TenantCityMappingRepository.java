package com.invoice.extraction.repository;

import com.invoice.extraction.entity.TenantCityMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantCityMappingRepository extends JpaRepository<TenantCityMapping, Long> {

    Optional<TenantCityMapping> findByTenantIdAndMappingTypeAndCode(
            Long tenantId, String mappingType, String code);

    List<TenantCityMapping> findByTenantIdAndMappingType(
            Long tenantId, String mappingType);
}