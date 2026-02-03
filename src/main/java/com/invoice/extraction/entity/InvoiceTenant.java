package com.invoice.extraction.entity;

import java.util.Set;
import java.util.HashSet;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "invoice_tenants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"identifiers", "fieldDefs", "blockConfigs", "fieldCalculations"}) 
public class InvoiceTenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_key", nullable = false, unique = true)
    private String tenantKey;          // 'TV', 'RADIO', 'INCOME_TAX'

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @OneToMany(mappedBy = "tenant", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<TenantIdentifier> identifiers = new HashSet<>();

    @OneToMany(mappedBy = "tenant", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<TenantFieldDef> fieldDefs = new HashSet<>();

    @OneToMany(mappedBy = "tenant", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<TenantBlockConfig> blockConfigs = new HashSet<>();

    @OneToMany(mappedBy = "tenant", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<TenantFieldCalculation> fieldCalculations = new HashSet<>();

    public enum TenantStatus { ACTIVE, INACTIVE }
}