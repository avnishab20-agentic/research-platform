package com.comeback.researchplatform.retrievalservice.web;

import com.comeback.researchplatform.retrievalservice.dto.*;
import java.util.List;

import com.comeback.researchplatform.retrievalservice.search.SearchService;
import org.springframework.web.bind.annotation.*;
import com.comeback.researchplatform.retrievalservice.quota.QuotaService;

@RestController
@RequestMapping("/api/v1")
public class RetrievalController {
    private final SearchService searchService;
    private final QuotaService quotaService;

    public RetrievalController(SearchService searchService, QuotaService quotaService) {
        this.searchService = searchService;
        this.quotaService = quotaService;
    }

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request){
        return searchService.search(request);
    }

    @PostMapping("/extract")
    public ExtractResponse extract(@RequestBody ExtractRequest request){
        return new ExtractResponse(List.of());
    }

    @GetMapping("/quota")
    public QuotaResponse quota(){
        return new QuotaResponse(quotaService.remaining());
    }
}
