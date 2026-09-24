package com.comeback.researchplatform.retrievalservice.web;

import com.comeback.researchplatform.retrievalservice.dto.ExtractRequest;
import com.comeback.researchplatform.retrievalservice.dto.ExtractResponse;
import com.comeback.researchplatform.retrievalservice.dto.QuotaResponse;
import com.comeback.researchplatform.retrievalservice.dto.SearchRequest;
import com.comeback.researchplatform.retrievalservice.dto.SearchResponse;
import com.comeback.researchplatform.retrievalservice.extract.ExtractService;
import com.comeback.researchplatform.retrievalservice.quota.QuotaExceededException;
import com.comeback.researchplatform.retrievalservice.quota.QuotaService;
import com.comeback.researchplatform.retrievalservice.search.SearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RetrievalController {
    private final SearchService searchService;
    private final QuotaService quotaService;
    private final ExtractService extractService;

    public RetrievalController(SearchService searchService, QuotaService quotaService, ExtractService extractService) {
        this.searchService = searchService;
        this.quotaService = quotaService;
        this.extractService = extractService;
    }

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request) {
        return searchService.search(request);
    }

    @PostMapping("/extract")
    public ExtractResponse extract(@RequestBody ExtractRequest request) {
        return extractService.extract(request);
    }

    @GetMapping("/quota")
    public QuotaResponse quota() {
        return new QuotaResponse(quotaService.remaining());
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<String> onQuotaExceeded(QuotaExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(e.getMessage());
    }
}
