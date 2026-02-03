package com.invoice.extraction.repository;

import com.invoice.extraction.entity.TenantFieldCalculation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TenantFieldCalculationRepository extends JpaRepository<TenantFieldCalculation, Long> {
    
    /**
     * Find all calculations for a tenant and block, ordered by priority
     */
    List<TenantFieldCalculation> findByTenantIdAndBlockNameOrderByPriorityAsc(Long tenantId, String blockName);
    
    /**
     * Find all calculations for a tenant
     */
    List<TenantFieldCalculation> findByTenantIdOrderByBlockNameAscPriorityAsc(Long tenantId);
}