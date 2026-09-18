package com.hmdp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.security.*;
import com.hmdp.service.memory.SemanticMemoryStore;
import com.hmdp.service.memory.UserProfileStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/internal/agent-tools/memory")
public class MemoryToolController {
    private final SemanticMemoryStore store;
    private final UserProfileStore profiles;

    public MemoryToolController(SemanticMemoryStore store, UserProfileStore profiles) {
        this.store=store; this.profiles=profiles;
    }

    @PostMapping("/bootstrap")
    public Map<String,Object> bootstrap(@RequestBody(required=false) Entities body,HttpServletRequest request) {
        var principal=require(request,AgentToolScopes.MEMORY_SELF_READ);
        return Map.of("enabled",store.enabled(),"user_id",principal.getUserId().toString(),
                "profile",profiles.readForAgent(principal.getUserId()),
                "items",store.recent(principal.getUserId()).stream().map(store::publicItem).toList(),
                "entityItems",store.byEntities(principal.getUserId(),body==null?List.of():body.entityIds())
                        .stream().map(store::publicItem).toList());
    }

    public record Entities(List<String> entityIds) {}

    public record Lookup(List<String> ids) {}
    @PostMapping("/lookup")
    public Map<String,Object> lookup(@RequestBody Lookup body,HttpServletRequest request) {
        var principal=require(request,AgentToolScopes.MEMORY_SELF_READ);
        if(body.ids()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return Map.of("items",store.lookup(principal.getUserId(),body.ids()).stream().map(store::publicItem).toList());
    }

    public record Event(String runId,String eventId,String type,JsonNode payload) {}
    @PostMapping("/events")
    public Map<String,Object> event(@RequestBody Event body,HttpServletRequest request) {
        var principal=require(request,AgentToolScopes.MEMORY_EVENT_APPEND);
        if(body.runId()==null || body.eventId()==null || body.type()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        store.appendEvent(principal.getUserId(),principal.getChatId(),principal.getUserMessageId(),
                body.runId(),body.eventId(),body.type(),body.payload());
        return Map.of("persisted",store.enabled());
    }

    private AgentToolPrincipal require(HttpServletRequest request,String scope) {
        var principal=(AgentToolPrincipal)request.getAttribute(AgentToolAuthFilter.PRINCIPAL_ATTRIBUTE);
        if(principal==null || !principal.hasScope(scope)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return principal;
    }
}
