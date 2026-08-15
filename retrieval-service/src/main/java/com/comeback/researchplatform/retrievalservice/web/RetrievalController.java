package com.comeback.researchplatform.retrievalservice.web;

import com.comeback.researchplatform.retrievalservice.dto.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RetrievalController {

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request){
        return new SearchResponse(List.of(),0 ,0);
    }

    @PostMapping("/extract")
    public ExtractResponse extract(@RequestBody ExtractRequest request){
        return new ExtractResponse(List.of());
    }

    @GetMapping("/quota")
    public QuotaResponse quota(){
        return new QuotaResponse(1000);
    }
}
