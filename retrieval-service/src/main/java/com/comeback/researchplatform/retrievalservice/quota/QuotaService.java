package com.comeback.researchplatform.retrievalservice.quota;

import com.comeback.researchplatform.retrievalservice.config.QuotaProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;


import java.time.Duration;
import java.time.LocalDate;


@Service
public class QuotaService {
    private final StringRedisTemplate redis;
    private final int dailyLimit;

    public QuotaService(StringRedisTemplate redis, QuotaProperties props) {
        this.redis = redis;
        this.dailyLimit = props.dailyLimit();
    }
    public void recordSpend(){
        String key=key();
        redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofHours(24));
    }

    public int remaining(){
        String used = redis.opsForValue().get(key());
        int spent = (used ==null )? 0 : Integer.parseInt(used);
        return Math.max(0, dailyLimit-spent);
    }
    private String key(){
        return "quota:v1" + LocalDate.now();
    }


}
