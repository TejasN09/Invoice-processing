package com.invoice.extraction.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Configuration for derived field calculations
 * Enables completely database-driven calculation logic
 */
@Entity
@Table(name = "tenant_field_calculations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantFieldCalculation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private InvoiceTenant tenant;

    @Column(name = "block_name", nullable = false)
    private String blockName;

    @Column(name = "target_field", nullable = false)
    private String targetField;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_type", nullable = false)
    private CalculationType calculationType;

    @Lob
    @Column(name = "source_fields", nullable = false, columnDefinition = "CLOB")
    @Convert(converter = JsonListConverter.class)
    private List<String> sourceFields;

    @Column(name = "calculation_formula", length = 1000)
    private String calculationFormula;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", nullable = false)
    private ResultType resultType = ResultType.DOUBLE;

    @Column(name = "apply_only_if_missing", nullable = false)
    private Boolean applyOnlyIfMissing = true;

    @Column(name = "priority", nullable = false)
    private Integer priority = 10;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum CalculationType {
        MULTIPLY,      // field1 * field2
        ADD,           // field1 + field2 + ...
        SUBTRACT,      // field1 - field2
        DIVIDE,        // field1 / field2
        PERCENTAGE,    // field1 * field2 / 100
        CUSTOM         // Use calculation_formula
    }

    public enum ResultType {
        INTEGER,
        DOUBLE
    }
}