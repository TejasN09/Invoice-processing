package com.invoice.extraction.repository;

import com.invoice.extraction.entity.TenantRadioConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantRadioConfigRepository extends JpaRepository<TenantRadioConfig, Long> {

    Optional<TenantRadioConfig> findByTenantId(Long tenantId);

    boolean existsByTenantId(Long tenantId);
}