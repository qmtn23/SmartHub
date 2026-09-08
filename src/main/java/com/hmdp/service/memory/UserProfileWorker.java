package com.hmdp.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.config.UserProfileProperties;
import com.hmdp.service.CustomerAgentClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.*;

@Slf4j
@Component
@EnableScheduling
public class UserProfileWorker {
    private final UserProfileStore store;
    private final UserProfileMerger merger;
    private final CustomerAgentClient agent;
    private final UserProfileProperties settings;
    private final Semaphore capacity = new Semaphore(1);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work,"user-profile-worker"); thread.setDaemon(true); return thread;
    });
    public UserProfileWorker(UserProfileStore store,UserProfileMerger merger,CustomerAgentClient agent,UserProfileProperties settings){
        this.store=store; this.merger=merger; this.agent=agent; this.settings=settings;
    }

    @Scheduled(fixedDelayString="${customer-profile.poll-interval-ms:5000}")
    public void poll(){
        if(!settings.isEnabled() || !capacity.tryAcquire()) return;
        boolean submitted=false;
        try {
            UserProfileStore.Batch batch=store.claim();
            if(batch==null) return;
            worker.submit(()-> {
                try{process(batch);}finally{capacity.release();}
            });
            submitted=true;
        } catch(RuntimeException e){
            log.warn("画像后台调度失败: {}",e.getClass().getSimpleName());
        } finally{if(!submitted) capacity.release();}
    }

    public void process(UserProfileStore.Batch batch){
        try {
            var messages=store.messages(batch);
            JsonNode tasks=store.relatedTasks(batch.userId());
            for(int attempt=0;attempt<3;attempt++){
                store.renew(batch);
                var base=store.snapshot(batch.userId());
                JsonNode diff=agent.extractUserProfile(merger.extractionView(base.content(),LocalDateTime.now()),tasks,messages);
                JsonNode next=merger.merge(base.content(),diff,messages);
                try{store.complete(batch,base,next);return;}
                catch(UserProfileStore.VersionConflict conflict){if(attempt==2) throw conflict;}
            }
        } catch(UserProfileStore.LostLease ignored){
            log.info("画像更新租约已失效");
        } catch(RuntimeException e){
            String error=e instanceof UserProfileStore.VersionConflict?"VERSION_CONFLICT":"PROFILE_UPDATE_FAILED";
            store.fail(batch,error);
            log.warn("画像更新失败，将按事件状态重试: {}",error);
        }
    }
    @PreDestroy public void close(){worker.shutdownNow();}
}
