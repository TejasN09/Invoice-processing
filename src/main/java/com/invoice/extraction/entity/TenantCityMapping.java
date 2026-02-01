package com.invoice.extraction.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tenant_city_mappings",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "mapping_type", "code"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantCityMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private InvoiceTenant tenant;

    @Column(name = "mapping_type", nullable = false)
    private String mappingType;        // 'CITY', 'PRODUCT', 'REGION'

    @Column(nullable = false)
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;
}