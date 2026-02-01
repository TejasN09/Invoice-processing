package com.invoice.extraction.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tenant_block_configs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "block_name"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantBlockConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private InvoiceTenant tenant;

    @Column(name = "block_name", nullable = false)
    private String blockName;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_mode", nullable = false)
    private BlockMode blockMode;       // GLOBAL or LINE_SPLIT

    @Column(name = "block_start_pattern", length = 500)
    private String blockStartPattern;  // nullable for GLOBAL mode

    @Column(name = "min_score", nullable = false)
    private int minScore;

    @Column(name = "qr_enabled", nullable = false)
    private boolean qrEnabled;

    public enum BlockMode { GLOBAL, LINE_SPLIT }
}