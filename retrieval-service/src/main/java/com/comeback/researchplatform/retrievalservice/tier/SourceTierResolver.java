package com.comeback.researchplatform.retrievalservice.tier;

import com.comeback.researchplatform.retrievalservice.config.SourceTierProperties;
import com.comeback.researchplatform.retrievalservice.url.UrlNormalizer;
import org.springframework.stereotype.Component;


@Component
public class SourceTierResolver {

    private final SourceTierProperties tierProperties;

    public SourceTierResolver(SourceTierProperties tierProperties){
        this.tierProperties = tierProperties;

    }
    public int resolveTier(String url){
        //1. Extract host from url:
       String host = UrlNormalizer.host(url);
       if(host==null)
           return 3;

        //3.  3. Check tier1, then tier2 — exact matches, in order:
        if(tierProperties.tier1().contains(host)) {
            return 1;
        }
        if(tierProperties.tier2().contains(host)){
            return 2;
        }

        //4.  4. Check tier4 patterns — this one's different, it's a glob, not an exact string. Loop the patterns and test each with a small private helper:
        for(String pattern : tierProperties.tier4Patterns()){
            if(matchesPattern(host, pattern)){
                return 4;
            }


        }
        return 3;


    }

    //5.  5. The glob-to-regex helper. Java has no built-in glob matcher for strings, so we convert * into .* (regex "match anything") and escape literal dots so they mean "dot," not "any character":

    private boolean matchesPattern(String host, String pattern){
        String regex = pattern.replace(".","\\.").replace("*",".*");
        return host.matches(regex);
    }


}
