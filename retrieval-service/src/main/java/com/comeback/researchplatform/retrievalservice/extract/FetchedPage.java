package com.comeback.researchplatform.retrievalservice.extract;

public record FetchedPage(String html, String status) {
    public boolean isOk(){
        return "OK".equals(status);
    }
}
