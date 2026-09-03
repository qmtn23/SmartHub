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
    private String runStatus;
    private String resolutionType;
    private AgentActionProposalDTO actionProposal;
    private AgentHandoffProposalDTO handoffProposal;
    private List<Map<String, Object>> routeHistory;
    private Integer handoffCount;
    private Integer modelCallCount;
    private Integer toolCallCount;
    private AgentUsageDTO usage;
    private String executionMode;
    private String planId;
    private Integer supervisorIterations;
    private Integer parallelTaskCount;
    private List<AgentTaskOutcomeDTO> taskOutcomes;
    private String orchestrator;
}
