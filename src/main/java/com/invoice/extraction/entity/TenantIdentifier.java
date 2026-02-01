package com.invoice.extraction.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tenant_identifiers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantIdentifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private InvoiceTenant tenant;

    @Column(nullable = false, length = 500)
    private String pattern;

    @Column(nullable = false)
    private int score;
}