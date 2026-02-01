package com.invoice.extraction.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "tenant_field_defs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "block_name", "field_name"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantFieldDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private InvoiceTenant tenant;

    @Column(name = "block_name", nullable = false)
    private String blockName;          // 'invoice', 'telecast', 'summary'

    @Column(name = "field_name", nullable = false)
    private String fieldName;          // 'date', 'rate', 'programme', 'cityName'

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", nullable = false)
    private FieldType fieldType;       // STRING, INTEGER, DOUBLE, DATE

    @Convert(converter = JsonListConverter.class)
    @Column(name = "extraction_patterns", nullable = false, columnDefinition = "JSON")
    private List<String> extractionPatterns;

    @Column(nullable = false)
    private int score;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired;

    @Column(name = "is_optional", nullable = false)
    private boolean isOptional;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    // ═══════════════════════════════════════════════════════════════════════
    // NEW: Context field support for hierarchical invoices (Radio, etc.)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * When TRUE, this field is a "context field" - its value is extracted once
     * and propagates to all subsequent data rows until a new value is detected.
     *
     * Example: In radio invoices, "cityName" is a context field:
     *   MUMBAI (MIRCHI)          ← Context field extracted here
     *   07:00-11:00  30  24  ... ← Data row (inherits cityName = "MUMBAI")
     *   18:00-23:00  30  10  ... ← Data row (inherits cityName = "MUMBAI")
     *   DELHI (MIRCHI)           ← Context switch (cityName = "DELHI")
     *
     * Lines that match context field patterns are NOT treated as data rows.
     */
    @Column(name = "is_context", nullable = false)
    @Builder.Default
    private boolean isContext = false;

    /**
     * When TRUE (and is_context=TRUE), detecting this field creates a new context scope.
     * Any buffered data rows are flushed before the new context is established.
     *
     * When FALSE, the field is added to the existing context without creating a boundary.
     *
     * Example:
     *   cityName: is_context=TRUE, context_reset_on_match=TRUE  (creates new scope)
     *   dateRange: is_context=TRUE, context_reset_on_match=FALSE (adds to city scope)
     *
     * Result:
     *   MUMBAI ← New context scope created
     *   01.01-31.01 ← Added to MUMBAI context
     *   Data row ← Inherits both cityName and dateRange
     */
    @Column(name = "context_reset_on_match", nullable = false)
    @Builder.Default
    private boolean contextResetOnMatch = true;

    public enum FieldType { STRING, INTEGER, DOUBLE, DATE }
}