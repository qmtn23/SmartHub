package com.hmdp.dto.agent;

import com.hmdp.dto.tool.BusinessReferenceDTO;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AgentRunResponseDTO {
    private String runId;
    private String reply;
    private String intent;
    private String activeAgent;
    private List<BusinessReferenceDTO> businessRefs;
    private Object structuredContent;
    private String traceId;
    private String graphVersion;
    private List<Map<String, Object>> routeHistory;
    private Integer handoffCount;
    private Integer modelCallCount;
    private Integer toolCallCount;
    private AgentUsageDTO usage;
}
