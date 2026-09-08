package com.hmdp.dto.agent;

import com.hmdp.dto.tool.BusinessReferenceDTO;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AgentTaskOutcomeDTO {
    private String taskId;
    private String targetAgent;
    private String intent;
    private String status;
    private String result;
    private String errorCode;
    private List<BusinessReferenceDTO> businessRefs;
    private Integer modelCallCount;
    private Integer toolCallCount;
    private Integer promptTokens;
    private Integer completionTokens;
    private Map<String, Object> metadata;
}
