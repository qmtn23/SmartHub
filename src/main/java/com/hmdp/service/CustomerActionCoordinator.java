package com.hmdp.service;

import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.entity.CustomerAgentRun;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerActionCoordinator {
    private final CustomerActionService actionService;
    private final CustomerAgentRunService agentRunService;

    public CustomerActionCoordinator(CustomerActionService actionService,
                                     CustomerAgentRunService agentRunService) {
        this.actionService = actionService;
        this.agentRunService = agentRunService;
    }

    @Transactional(rollbackFor = Exception.class)
    public CustomerActionService.PreparationResult prepareAndPersist(
            CustomerAgentRun run, CustomerChatMessage userMessage, AgentRunResponseDTO response,
            CustomerChatMessage assistantMessage, CustomerChat chat) {
        CustomerActionService.PreparationResult preparation =
                actionService.prepare(run, userMessage, response);
        if (preparation.getAction() != null && preparation.isCreated()) {
            agentRunService.completeAwaiting(run.getRunId(), response, assistantMessage, chat,
                    preparation.getAction().getActionRequestId(), preparation.getAction().getActionType());
        }
        return preparation;
    }
}
