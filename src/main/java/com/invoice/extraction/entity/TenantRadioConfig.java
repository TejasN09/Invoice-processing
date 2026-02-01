package com.invoice.extraction.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Radio-specific extraction config.
 * Radio invoices have a fundamentally different row structure than TV:
 *   - Rows are segmented by CITY context, not by date/programme blocks.
 *   - Dates and timebands are extracted via dedicated regex (not generic field defs).
 *   - Some stations (Radio City) use 3-letter city codes that need mapping.
 *
 * The generic fields (spots, fct, rate, amount, duration) still live in
 * tenant_field_defs with block_name = 'invoice'.  This table only holds
 * the patterns that are structurally unique to radio.
 */
@Entity
@Table(name = "tenant_radio_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"tenant"}) 
public class TenantRadioConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    private InvoiceTenant tenant;

    /** Regex to detect a city name or code from a line of text.
     *  Group 1 = the city identifier (name or code).
     *  Examples:
     *    Radio Mirchi:  ^([A-Z]+)\s*\(MIRCHI
     *    Big FM:        (?i)\b(Mumbai|Delhi|...)\b
     *    Radio City:    ^([A-Z]{3})$          ← produces a 3-letter code, needs city_mapping lookup
     */
    @Column(name = "city_pattern", length = 1000)
    private String cityPattern;

    /** Regex to extract start and end dates.
     *  Group 1 = start date, Group 2 = end date (if present).
     *  Examples:
     *    (\d{2}\.\d{2}\.\d{4})\s+(\d{2}\.\d{2}\.\d{4})
     *    (\d{2}/\d{2}/\d{4})\s+To\s+(\d{2}/\d{2}/\d{4})
     */
    @Column(name = "date_pattern", length = 500)
    private String datePattern;

    /** Regex to extract timeband start and end.
     *  Group 1 = start time, Group 2 = end time.
     *  Can be NULL (FM Tadka has no timeband).
     *  Can also match a keyword like "FCT" instead of a time range (MY FM).
     */
    @Column(name = "time_pattern", length = 500)
    private String timePattern;

    /** Optional row-start pattern override.
     *  When set, this takes priority over block_start_pattern from tenant_block_configs
     *  for detecting where a new data row begins.
     *  Examples:
     *    Red FM:         \d{2}\.\d{2}\.\d{4}.*?\d{2}:\d{2}:\d{2}
     *    Radio City:     ^\(\d+\)
     *    Radio Nation:   ^\(\d+\)\s*\d{2}-\d{2}\s*\d{2}:\d{2}-\d{2}:\d{2}
     */
    @Column(name = "row_start_pattern", length = 500)
    private String rowStartPattern;
}