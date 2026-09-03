package com.hmdp.dto.agent;

import com.hmdp.dto.tool.BusinessReferenceDTO;
import lombok.Data;

import java.util.List;

@Data
public class AgentHandoffProposalDTO {
    private String reasonCode;
    private Boolean userRequested;
    private String summary;
    private List<String> attemptedTasks;
    private List<String> failedTasks;
    private List<BusinessReferenceDTO> businessRefs;
}
