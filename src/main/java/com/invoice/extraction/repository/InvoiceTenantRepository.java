package com.invoice.extraction.repository;

import com.invoice.extraction.entity.InvoiceTenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InvoiceTenantRepository extends JpaRepository<InvoiceTenant, Long> {

    Optional<InvoiceTenant> findByTenantKey(String tenantKey);

    List<InvoiceTenant> findByStatus(InvoiceTenant.TenantStatus status);

    // Eagerly fetch everything needed for extraction in one query
    @Query("""
        SELECT t FROM InvoiceTenant t
        LEFT JOIN FETCH t.identifiers
        LEFT JOIN FETCH t.fieldDefs
        LEFT JOIN FETCH t.blockConfigs
        WHERE t.status = 'ACTIVE'
    """)
    List<InvoiceTenant> findAllActiveWithConfig();
}