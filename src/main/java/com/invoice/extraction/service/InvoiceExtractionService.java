package com.invoice.extraction.service;

import com.invoice.extraction.entity.*;
import com.invoice.extraction.model.ExtractionResult;
import com.invoice.extraction.repository.TenantRadioConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Top-level orchestrator — UPDATED to route TV vs Radio correctly.
 *
 * The routing decision is simple:
 *   - If the identified tenant has a row in tenant_radio_configs → RadioExtractionEngine
 *   - Otherwise → GenericExtractionEngine (TV)
 *
 * This is the ONLY place that knows about the two engines.
 * Everything downstream is tenant-config-driven.
 */
@Service
@Slf4j
public class InvoiceExtractionService {

    private final TenantIdentificationService identificationService;
    private final GenericExtractionEngine tvEngine;
    private final RadioExtractionEngine radioEngine;
    private final TenantRadioConfigRepository radioConfigRepo;

    public InvoiceExtractionService(
            TenantIdentificationService identificationService,
            GenericExtractionEngine tvEngine,
            RadioExtractionEngine radioEngine,
            TenantRadioConfigRepository radioConfigRepo) {

        this.identificationService = identificationService;
        this.tvEngine = tvEngine;
        this.radioEngine = radioEngine;
        this.radioConfigRepo = radioConfigRepo;
    }

    public ExtractionResult extract(MultipartFile file) throws Exception {
        // 1. Pull text out of PDF
        String pdfText = extractText(file);

        if (pdfText == null || pdfText.isBlank()) {
            return ExtractionResult.empty("PDF contains no extractable text");
        }

        // 2. Identify tenant
        InvoiceTenant tenant = identificationService.identifyTenant(pdfText)
                .orElseThrow(() -> new RuntimeException(
                        "Could not identify invoice type. No tenant matched."));

        log.info("Identified tenant: {} for file: {}", tenant.getTenantKey(), file.getOriginalFilename());

        // 3. Route to the correct engine
        ExtractionResult result = isRadioTenant(tenant)
                ? radioEngine.extract(pdfText, tenant)
                : tvEngine.extract(pdfText, tenant);

        // 4. QR decode for summary if any block has qr_enabled
        // tenant.getBlockConfigs().stream()
        //         .filter(TenantBlockConfig::isQrEnabled)
        //         .findFirst()
        //         .ifPresent(cfg -> {
        //             try {
        //                 Double qrAmount = qrService.tryQrDecode(file);
        //                 if (qrAmount != null) {
        //                     result.setQrFinalAmount(qrAmount);
        //                     log.info("QR decode succeeded: {}", qrAmount);
        //                 }
        //             } catch (Exception e) {
        //                 log.warn("QR extraction failed: {}", e.getMessage());
        //             }
        //         });

        return result;
    }

    /**
     * A tenant is "radio" if it has a row in tenant_radio_configs.
     * This is the single source of truth for routing — no hardcoded tenant keys.
     */
    private boolean isRadioTenant(InvoiceTenant tenant) {
        return radioConfigRepo.existsByTenantId(tenant.getId());
    }

    private String extractText(MultipartFile file) throws Exception {
        try (PDDocument doc = Loader.loadPDF(
                new RandomAccessReadBuffer(file.getInputStream()))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }
}