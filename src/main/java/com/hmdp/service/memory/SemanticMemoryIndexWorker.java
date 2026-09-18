package com.hmdp.service.memory;

import com.hmdp.config.SemanticMemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Component
public class SemanticMemoryIndexWorker {
    private final JdbcTemplate jdbc;
    private final SemanticMemoryStore store;
    private final SemanticMemoryProperties settings;
    private final RestTemplate http;
    private final String url;
    private final String apiKey;

    public SemanticMemoryIndexWorker(JdbcTemplate jdbc, SemanticMemoryStore store, SemanticMemoryProperties settings,
            @Qualifier("agentRestTemplate") RestTemplate http,
            @Value("${agent-service.base-url}") String url, @Value("${agent-service.api-key:}") String apiKey) {
        this.jdbc=jdbc; this.store=store; this.settings=settings; this.http=http;
        this.url=url.replaceAll("/+$",""); this.apiKey=apiKey;
    }

    @Scheduled(fixedDelayString="${customer-semantic-memory.poll-interval-ms:5000}")
    public void poll() {
        if (!settings.isEnabled()) return;
        try {
            store.backfill();
            store.expire();
            jdbc.update("UPDATE tb_customer_memory_index_job SET status='FAILED',error_code='LEASE_EXHAUSTED' "
                    + "WHERE status='RUNNING' AND lease_until<NOW() AND attempts>=?",settings.getMaxAttempts());
            var jobs=jdbc.queryForList("SELECT * FROM tb_customer_memory_index_job WHERE attempts<? AND "
                    + "((status='PENDING' AND next_attempt_at<=NOW()) OR (status='RUNNING' AND lease_until<NOW())) "
                    + "ORDER BY next_attempt_at LIMIT 4",settings.getMaxAttempts());
            for (var job:jobs) process(job);
        } catch (RuntimeException error) {
            log.warn("长期记忆索引调度失败: {}",error.getClass().getSimpleName());
        }
    }

    private void process(Map<String,Object> job) {
        String id=job.get("memory_id").toString();
        long version=((Number)job.get("memory_version")).longValue();
        String lease=UUID.randomUUID().toString().replace("-","");
        int acquired=jdbc.update("UPDATE tb_customer_memory_index_job SET status='RUNNING',lease_id=?,"
                + "lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND),attempts=attempts+1,update_time=NOW() "
                + "WHERE memory_id=? AND memory_version=? AND attempts<? AND "
                + "((status='PENDING' AND next_attempt_at<=NOW()) OR (status='RUNNING' AND lease_until<NOW()))",
                lease,id,version,settings.getMaxAttempts());
        if(acquired!=1) return;
        try {
            var rows=jdbc.queryForList("SELECT * FROM tb_customer_memory_item WHERE memory_id=?",id);
            if(rows.isEmpty()) throw new IllegalStateException("MISSING_MEMORY_ITEM");
            var row=rows.get(0);
            boolean current=((Number)row.get("version")).longValue()==version;
            boolean upsert=current && "ACTIVE".equals(row.get("status"));
            Map<String,Object> payload=new LinkedHashMap<>();
            payload.put("memory_id",id); payload.put("version",version);
            payload.put("user_id",row.get("user_id").toString());
            payload.put("operation",upsert?"UPSERT":"DELETE");
            payload.put("content",upsert?row.get("content"):"");
            HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Agent-Service-Key",apiKey);
            http.exchange(url+"/v1/customer-service/memory/index",HttpMethod.POST,new HttpEntity<>(payload,headers),String.class);
            jdbc.update("UPDATE tb_customer_memory_index_job SET status='DONE',lease_id=NULL,lease_until=NULL,error_code=NULL,update_time=NOW() "
                    + "WHERE memory_id=? AND memory_version=? AND lease_id=?",id,version,lease);
        } catch(RuntimeException error) {
            int attempts=((Number)job.get("attempts")).intValue()+1;
            jdbc.update("UPDATE tb_customer_memory_index_job SET status=IF(attempts>=?,'FAILED','PENDING'),"
                    + "next_attempt_at=DATE_ADD(NOW(),INTERVAL ? SECOND),lease_id=NULL,lease_until=NULL,error_code='INDEX_FAILED',update_time=NOW() "
                    + "WHERE memory_id=? AND memory_version=? AND lease_id=?",settings.getMaxAttempts(),
                    Math.min(300,5*(1<<Math.min(attempts,5))),id,version,lease);
            log.warn("长期记忆索引失败，将重试: memoryId={}, version={}",id,version);
        }
    }
}
