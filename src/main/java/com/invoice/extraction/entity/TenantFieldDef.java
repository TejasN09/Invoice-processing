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
    private String fieldName;          // 'date', 'rate', 'programme'

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

    public enum FieldType { STRING, INTEGER, DOUBLE, DATE }
}