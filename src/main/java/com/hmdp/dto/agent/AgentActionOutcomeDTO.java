package com.hmdp.dto.agent;

import com.hmdp.dto.tool.BusinessReferenceDTO;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class AgentActionOutcomeDTO {
    private String status;
    private String actionType;
    private String targetBizType;
    private Long targetBizId;
    private String resultCode;
    private String message;
    private List<BusinessReferenceDTO> businessRefs;
}
