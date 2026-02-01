package com.invoice.extraction.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Tracks which summary fields should attempt QR-code extraction before
 * falling back to regex.  In practice this is always 'finalAmount' —
 * Indian GST QR codes embed a JWT whose payload contains TotInvVal.
 *
 * The extraction engine checks this table when processing a 'summary' block:
 *   1. If qr_enabled = true for 'finalAmount', attempt QR decode first.
 *   2. If QR returns a value, use it and skip the regex patterns.
 *   3. If QR fails or returns null, fall through to the regex patterns
 *      stored in tenant_field_defs for the same (tenant, 'summary', 'finalAmount') row.
 */
@Entity
@Table(name = "tenant_summary_rules",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "field_name"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantSummaryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private InvoiceTenant tenant;

    @Column(name = "field_name", nullable = false)
    private String fieldName;       // 'finalAmount'

    @Column(name = "qr_enabled", nullable = false)
    private boolean qrEnabled;
}