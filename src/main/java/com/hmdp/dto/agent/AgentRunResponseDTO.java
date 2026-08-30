package com.hmdp.dto.agent;

import com.hmdp.dto.tool.BusinessReferenceDTO;
import lombok.Data;

import java.util.List;

@Data
public class AgentRunResponseDTO {
    private String runId;
    private String reply;
    private String intent;
    private String activeAgent;
    private List<BusinessReferenceDTO> businessRefs;
    private Object structuredContent;
    private String traceId;
}
