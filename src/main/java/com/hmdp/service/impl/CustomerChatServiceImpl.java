package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AgentClientException;
import com.hmdp.config.ChatBusinessException;
import com.hmdp.dto.ChatReplyDTO;
import com.hmdp.dto.ChatRequest;
import com.hmdp.dto.agent.AgentMessageDTO;
import com.hmdp.dto.agent.AgentRunResumeRequestDTO;
import com.hmdp.dto.agent.AgentRunRequestDTO;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.dto.agent.AgentToolTokensDTO;
import com.hmdp.entity.CustomerAgentRun;
import com.hmdp.entity.CustomerActionRequest;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerImChat;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.CustomerImChatMapper;
import com.hmdp.security.AgentToolScopes;
import com.hmdp.security.AgentToolTokenService;
import com.hmdp.service.CustomerAgentClient;
import com.hmdp.service.CustomerActionService;
import com.hmdp.service.CustomerActionCoordinator;
import com.hmdp.service.CustomerAgentRunService;
import com.hmdp.service.IConversationMemoryService;
import com.hmdp.service.ICustomerChatService;
import com.hmdp.service.ICustomerHandoffService;
import com.hmdp.utils.CustomerToolContext;
import com.hmdp.utils.RedisIdWorker;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.hmdp.utils.CustomerChatConstants.*;

@Service
public class CustomerChatServiceImpl implements ICustomerChatService {

    private final CustomerImChatMapper imChatMapper;
    private final CustomerChatMapper chatMapper;
    private final CustomerChatMessageMapper messageMapper;
    private final CustomerAgentClient customerAgentClient;
    private final CustomerAgentRunService agentRunService;
    private final CustomerActionService actionService;
    private final CustomerActionCoordinator actionCoordinator;
    private final ICustomerHandoffService handoffService;
    private final AgentToolTokenService tokenService;
    private final IConversationMemoryService conversationMemoryService;
    private final RedisIdWorker redisIdWorker;
    private final ObjectMapper objectMapper;
    @Value("${customer-actions.auto-handoff-enabled:false}")
    private boolean autoHandoffEnabled;

    public CustomerChatServiceImpl(CustomerImChatMapper imChatMapper,
                                   CustomerChatMapper chatMapper,
                                   CustomerChatMessageMapper messageMapper,
                                   CustomerAgentClient customerAgentClient,
                                   CustomerAgentRunService agentRunService,
                                   CustomerActionService actionService,
                                   CustomerActionCoordinator actionCoordinator,
                                   ICustomerHandoffService handoffService,
                                   AgentToolTokenService tokenService,
                                   IConversationMemoryService conversationMemoryService,
                                   RedisIdWorker redisIdWorker,
                                   ObjectMapper objectMapper) {
        this.imChatMapper = imChatMapper;
        this.chatMapper = chatMapper;
        this.messageMapper = messageMapper;
        this.customerAgentClient = customerAgentClient;
        this.agentRunService = agentRunService;
        this.actionService = actionService;
        this.actionCoordinator = actionCoordinator;
        this.handoffService = handoffService;
        this.tokenService = tokenService;
        this.conversationMemoryService = conversationMemoryService;
        this.redisIdWorker = redisIdWorker;
        this.objectMapper = objectMapper;
    }

    @Override
    public CustomerImChat createImChat(Long userId, String title) {
        LocalDateTime now = LocalDateTime.now();
        CustomerImChat imChat = new CustomerImChat()
                .setImChatId(redisIdWorker.nextId("im_chat"))
                .setUserId(userId)
                .setTitle(normalizeTitle(title))
                .setHandlerType(HANDLER_TYPE_BOT)
                .setStatus(IM_CHAT_STATUS_BOT_ACTIVE)
                .setLastMessageTime(now)
                .setCreateTime(now)
                .setUpdateTime(now);
        imChatMapper.insert(imChat);
        return imChat;
    }

    @Override
    public IPage<CustomerImChat> listImChats(Long userId, int pageNo, int pageSize) {
        Page<CustomerImChat> page = new Page<>(normalizePageNo(pageNo), normalizePageSize(pageSize));
        return imChatMapper.selectPage(page, new LambdaQueryWrapper<CustomerImChat>()
                .eq(CustomerImChat::getUserId, userId)
                .orderByDesc(CustomerImChat::getLastMessageTime)
                .orderByDesc(CustomerImChat::getImChatId));
    }

    @Override
    public CustomerImChat getImChat(Long userId, Long imChatId) {
        return requireOwnedImChat(userId, imChatId);
    }

    @Override
    public CustomerChat createOrResumeChat(Long userId, Long imChatId) {
        CustomerImChat imChat = requireOwnedImChat(userId, imChatId);
        if (IM_CHAT_STATUS_CLOSED.equals(imChat.getStatus())) {
            throw new ChatBusinessException("长会话已关闭，请新建会话");
        }

        CustomerChat activeChat = chatMapper.selectOne(new LambdaQueryWrapper<CustomerChat>()
                .eq(CustomerChat::getImChatId, imChatId)
                .eq(CustomerChat::getUserId, userId)
                .eq(CustomerChat::getStatus, CHAT_STATUS_ACTIVE)
                .orderByDesc(CustomerChat::getStartTime)
                .last("LIMIT 1"));

        if (isHumanMode(imChat)) {
            if (activeChat == null) {
                throw new ChatBusinessException("人工接待短会话不存在，请重新查询转人工状态");
            }
            return activeChat;
        }

        LocalDateTime now = LocalDateTime.now();
        if (activeChat != null && !isExpired(activeChat, now)) {
            return activeChat;
        }
        if (activeChat != null) {
            conversationMemoryService.finalizeChat(imChat, activeChat, now);
        }

        CustomerChat chat = new CustomerChat()
                .setChatId(redisIdWorker.nextId("chat"))
                .setImChatId(imChatId)
                .setUserId(userId)
                .setStatus(CHAT_STATUS_ACTIVE)
                .setStartTime(now)
                .setLastActiveTime(now);
        chatMapper.insert(chat);
        return chat;
    }

    @Override
    public void endChat(Long userId, Long imChatId, Long chatId) {
        CustomerImChat imChat = requireOwnedImChat(userId, imChatId);
        if (isHumanMode(imChat)) {
            throw new ChatBusinessException("人工接待短会话不能通过该接口结束");
        }
        CustomerChat chat = requireActiveChat(userId, imChatId, chatId);
        conversationMemoryService.finalizeChat(imChat, chat, LocalDateTime.now());
    }

    @Override
    public ChatReplyDTO sendMessage(Long userId, ChatRequest request) {
        validateSendRequest(request);
        CustomerImChat imChat = requireOwnedImChat(userId, request.getImChatId());
        if (IM_CHAT_STATUS_CLOSED.equals(imChat.getStatus())) {
            throw new ChatBusinessException("长会话已关闭，请新建会话");
        }
        CustomerChat chat = requireActiveChat(userId, request.getImChatId(), request.getChatId());
        if (IM_CHAT_STATUS_BOT_ACTIVE.equals(imChat.getStatus())
                && isExpired(chat, LocalDateTime.now())) {
            conversationMemoryService.finalizeChat(imChat, chat, LocalDateTime.now());
            throw new ChatBusinessException("短会话已超时，请重新进入该长会话");
        }

        CustomerChatMessage existingUserMessage = findUserMessage(userId, request.getClientMessageId());
        if (existingUserMessage != null) {
            return existingReply(existingUserMessage, imChat, chat);
        }

        LocalDateTime now = LocalDateTime.now();
        CustomerChatMessage userMessage = new CustomerChatMessage()
                .setMessageId(redisIdWorker.nextId("chat_message"))
                .setImChatId(request.getImChatId())
                .setChatId(request.getChatId())
                .setUserId(userId)
                .setSenderType(SENDER_USER)
                .setMessageType(MESSAGE_TYPE_TEXT)
                .setContent(request.getMessage().trim())
                .setClientMessageId(request.getClientMessageId().trim())
                .setCreateTime(now);
        if (isHumanMode(imChat)) {
            messageMapper.insert(userMessage);
            updateConversationActivity(imChat, chat, userMessage.getContent(), now);
            return toReply(userMessage, null, imChat);
        }
        if (!IM_CHAT_STATUS_BOT_ACTIVE.equals(imChat.getStatus())) {
            throw new ChatBusinessException("当前长会话暂时不能发送消息");
        }

        CustomerActionRequest activeAction = actionService.findActive(userId, request.getImChatId());
        CustomerActionService.ConfirmationDecision confirmation = actionService.classify(userMessage.getContent());
        if (activeAction != null && confirmation != null
                && confirmation != CustomerActionService.ConfirmationDecision.NONE) {
            CustomerActionService.ExecutionResult execution =
                    actionService.executeMessage(userMessage, activeAction, confirmation);
            updateConversationActivity(imChat, chat, userMessage.getContent(), now);
            return completeActionMessage(userMessage, imChat, chat, execution);
        }
        if (activeAction == null && confirmation != null
                && confirmation != CustomerActionService.ConfirmationDecision.NONE) {
            return saveDeterministicReply(userMessage, imChat, chat, "当前没有待确认操作。", null);
        }

        CustomerAgentRun run;
        try {
            run = agentRunService.createPendingWithUserMessage(userMessage);
        } catch (DuplicateKeyException e) {
            CustomerChatMessage concurrentMessage = findUserMessage(userId, request.getClientMessageId());
            if (concurrentMessage == null) {
                throw e;
            }
            return existingReply(concurrentMessage, imChat, chat);
        }
        updateConversationActivity(imChat, chat, userMessage.getContent(), now);
        return processAgentMessage(userMessage, imChat, chat, run);
    }

    @Override
    public IPage<CustomerChatMessage> listMessages(Long userId, Long imChatId, int pageNo, int pageSize) {
        requireOwnedImChat(userId, imChatId);
        Page<CustomerChatMessage> page = new Page<>(normalizePageNo(pageNo), normalizePageSize(pageSize));
        return messageMapper.selectPage(page, new LambdaQueryWrapper<CustomerChatMessage>()
                .eq(CustomerChatMessage::getUserId, userId)
                .eq(CustomerChatMessage::getImChatId, imChatId)
                .orderByDesc(CustomerChatMessage::getMessageId));
    }

    @Override
    public void closeImChat(Long userId, Long imChatId) {
        CustomerImChat imChat = requireOwnedImChat(userId, imChatId);
        if (IM_CHAT_STATUS_CLOSED.equals(imChat.getStatus())) {
            return;
        }
        if (isHumanMode(imChat)) {
            throw new ChatBusinessException("请等待人工接待结束后再关闭长会话");
        }
        LocalDateTime now = LocalDateTime.now();
        List<CustomerChat> activeChats = chatMapper.selectList(new LambdaQueryWrapper<CustomerChat>()
                .eq(CustomerChat::getImChatId, imChatId)
                .eq(CustomerChat::getUserId, userId)
                .eq(CustomerChat::getStatus, CHAT_STATUS_ACTIVE));
        for (CustomerChat chat : activeChats) {
            conversationMemoryService.finalizeChat(imChat, chat, now);
        }
        imChat.setStatus(IM_CHAT_STATUS_CLOSED);
        imChat.setCloseTime(now);
        imChat.setUpdateTime(now);
        imChatMapper.updateById(imChat);
    }

    private CustomerImChat requireOwnedImChat(Long userId, Long imChatId) {
        if (imChatId == null) {
            throw new ChatBusinessException("imChatId不能为空");
        }
        CustomerImChat imChat = imChatMapper.selectOne(new LambdaQueryWrapper<CustomerImChat>()
                .eq(CustomerImChat::getImChatId, imChatId)
                .eq(CustomerImChat::getUserId, userId));
        if (imChat == null) {
            throw new ChatBusinessException("长会话不存在或无权访问");
        }
        return imChat;
    }

    private CustomerChat requireActiveChat(Long userId, Long imChatId, Long chatId) {
        if (chatId == null) {
            throw new ChatBusinessException("chatId不能为空");
        }
        CustomerChat chat = chatMapper.selectOne(new LambdaQueryWrapper<CustomerChat>()
                .eq(CustomerChat::getChatId, chatId)
                .eq(CustomerChat::getImChatId, imChatId)
                .eq(CustomerChat::getUserId, userId)
                .eq(CustomerChat::getStatus, CHAT_STATUS_ACTIVE));
        if (chat == null) {
            throw new ChatBusinessException("短会话不存在、已结束或无权访问");
        }
        return chat;
    }

    private CustomerChatMessage findUserMessage(Long userId, String clientMessageId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<CustomerChatMessage>()
                .eq(CustomerChatMessage::getUserId, userId)
                .eq(CustomerChatMessage::getClientMessageId, clientMessageId)
                .eq(CustomerChatMessage::getSenderType, SENDER_USER)
                .last("LIMIT 1"));
    }

    private ChatReplyDTO existingReply(CustomerChatMessage userMessage, CustomerImChat imChat,
                                       CustomerChat chat) {
        CustomerChatMessage assistantMessage = messageMapper.selectOne(
                new LambdaQueryWrapper<CustomerChatMessage>()
                        .eq(CustomerChatMessage::getReplyToMessageId, userMessage.getMessageId())
                        .eq(CustomerChatMessage::getSenderType, SENDER_ASSISTANT)
                        .last("LIMIT 1"));
        if (assistantMessage == null) {
            if (isHumanMode(imChat)) {
                return toReply(userMessage, null, imChat);
            }
            CustomerActionService.ExecutionResult recovered =
                    actionService.recoverExecutionResult(userMessage.getClientMessageId());
            if (recovered != null) {
                return completeActionMessage(userMessage, imChat, chat, recovered);
            }
            CustomerAgentRun run = agentRunService.findByUserMessageId(userMessage.getMessageId());
            if (run == null) {
                run = agentRunService.createPending(userMessage);
            }
            return processAgentMessage(userMessage, imChat, chat, run);
        }
        ChatReplyDTO reply = toReply(userMessage, assistantMessage, imChat);
        CustomerActionRequest action = actionService.findByProposalUserMessageId(userMessage.getMessageId());
        if (action != null) {
            reply.setRunStatus(CustomerActionService.AWAITING_CONFIRMATION.equals(action.getStatus())
                    ? "AWAITING_CONFIRMATION" : "COMPLETED");
            reply.setActionRequestId(action.getActionRequestId());
        }
        return reply;
    }

    private ChatReplyDTO processAgentMessage(CustomerChatMessage userMessage,
                                              CustomerImChat imChat,
                                              CustomerChat chat,
                                              CustomerAgentRun run) {
        if (!agentRunService.claim(run.getRunId())) {
            throw new ChatBusinessException("该消息正在处理中，请稍后重试");
        }

        AgentRunResponseDTO agentResponse;
        try {
            CustomerToolContext toolContext = new CustomerToolContext(userMessage.getUserId(),
                    userMessage.getImChatId(), userMessage.getChatId(), userMessage.getMessageId());
            AgentRunRequestDTO agentRequest = new AgentRunRequestDTO();
            agentRequest.setRequestId(run.getRequestId());
            agentRequest.setThreadId(String.valueOf(userMessage.getChatId()));
            agentRequest.setImChatId(userMessage.getImChatId());
            agentRequest.setUserMessageId(userMessage.getMessageId());
            agentRequest.setMessage(userMessage.getContent());
            agentRequest.setLongTermSummary(conversationMemoryService.getLongTermMemory(imChat));
            agentRequest.setRecentMessages(loadRecentAgentMessages(userMessage));
            agentRequest.setPreviousActiveAgent(chat.getActiveAgent());
            agentRequest.setGraphVersion("v4");
            agentRequest.setPendingAction(actionService.toPendingContext(
                    actionService.findActive(userMessage.getUserId(), userMessage.getImChatId())));
            agentRequest.setToolAccessTokens(new AgentToolTokensDTO(
                    tokenService.issue(toolContext, AgentToolScopes.transactionScopes()),
                    tokenService.issue(toolContext, AgentToolScopes.discoveryScopes())));
            agentResponse = customerAgentClient.invoke(agentRequest);
        } catch (AgentClientException e) {
            agentRunService.completeFailure(run.getRunId(), e.getErrorCode(), e.isRetryable());
            throw new ChatBusinessException(e.getMessage());
        } catch (IllegalStateException e) {
            agentRunService.completeFailure(run.getRunId(), "AGENT_CONFIGURATION_ERROR", false);
            throw new ChatBusinessException("智能客服服务配置错误，请联系管理员");
        } catch (RuntimeException e) {
            agentRunService.completeFailure(run.getRunId(), "AGENT_INTERNAL_ERROR", true);
            throw new ChatBusinessException("智能客服服务暂时不可用，请稍后重试或转人工");
        }

        LocalDateTime replyTime = LocalDateTime.now();
        CustomerChatMessage assistantMessage = new CustomerChatMessage()
                .setMessageId(redisIdWorker.nextId("chat_message"))
                .setImChatId(userMessage.getImChatId())
                .setChatId(userMessage.getChatId())
                .setUserId(userMessage.getUserId())
                .setSenderType(SENDER_ASSISTANT)
                .setMessageType(MESSAGE_TYPE_TEXT)
                .setContent(agentResponse.getReply().trim())
                .setStructuredContent(toJson(agentResponse.getStructuredContent()))
                .setReplyToMessageId(userMessage.getMessageId())
                .setCreateTime(replyTime);
        if (agentResponse.getIntent() != null && !agentResponse.getIntent().isBlank()) {
            chat.setIntent(agentResponse.getIntent());
        }
        if (agentResponse.getActiveAgent() != null && !agentResponse.getActiveAgent().isBlank()) {
            chat.setActiveAgent(agentResponse.getActiveAgent());
        }
        chat.setLastActiveTime(replyTime);

        boolean javaHandoffRequired = false;
        if ("AWAITING_CONFIRMATION".equals(agentResponse.getRunStatus())) {
            CustomerActionService.PreparationResult preparation =
                    actionCoordinator.prepareAndPersist(
                            run, userMessage, agentResponse, assistantMessage, chat);
            if (preparation.getAction() != null && preparation.isCreated()) {
                updateImChatAfterAgent(imChat, agentResponse.getIntent(), replyTime);
                ChatReplyDTO reply = toReply(userMessage, assistantMessage, imChat);
                reply.setRunStatus("AWAITING_CONFIRMATION");
                reply.setActionRequestId(preparation.getAction().getActionRequestId());
                return reply;
            }
            if (preparation.isRequiresHandoff()) {
                assistantMessage.setContent(autoHandoffEnabled
                        ? "订单状态需要人工核实，我将为你转接人工客服继续处理。"
                        : "订单状态需要人工核实，请点击转人工入口继续处理。");
                assistantMessage.setStructuredContent(null);
                agentResponse.setRunStatus(autoHandoffEnabled ? "HANDOFF_REQUESTED" : "COMPLETED");
                javaHandoffRequired = autoHandoffEnabled;
            } else {
                assistantMessage.setContent(preparation.getAction() == null
                        ? "该操作当前未开放，请联系人工客服处理。"
                        : "当前已有一个待确认操作，请先回复“确认”或“算了”。");
                agentResponse.setRunStatus("COMPLETED");
                agentResponse.setResolutionType("RESPONSE_ONLY");
                agentResponse.setActionProposal(null);
                assistantMessage.setStructuredContent(null);
            }
        }
        agentRunService.completeSuccess(run.getRunId(), agentResponse, assistantMessage, chat);
        updateImChatAfterAgent(imChat, agentResponse.getIntent(), replyTime);
        if ("HANDOFF_REQUESTED".equals(agentResponse.getRunStatus())
                && ((javaHandoffRequired && autoHandoffEnabled) || allowAgentHandoff(userMessage, agentResponse))) {
            String reason = agentResponse.getHandoffProposal() == null
                    ? "ACTION_STATE_CONFLICT" : agentResponse.getHandoffProposal().getReasonCode();
            handoffService.requestAgentHandoff(userMessage.getUserId(), userMessage.getImChatId(),
                    userMessage.getChatId(), reason, run.getRunId(), null);
            imChat.setStatus(IM_CHAT_STATUS_HUMAN_PENDING);
            imChat.setHandlerType(HANDLER_TYPE_HUMAN);
        }
        ChatReplyDTO reply = toReply(userMessage, assistantMessage, imChat);
        reply.setRunStatus(agentResponse.getRunStatus());
        return reply;
    }

    private ChatReplyDTO completeActionMessage(CustomerChatMessage userMessage,
                                               CustomerImChat imChat,
                                               CustomerChat chat,
                                               CustomerActionService.ExecutionResult execution) {
        CustomerActionRequest action = execution.getAction();
        if (execution.isRequiresHandoff() && !autoHandoffEnabled) {
            execution.getOutcome().setMessage("订单状态已变化，请点击转人工入口继续处理。");
        }
        AgentRunResumeRequestDTO resume = new AgentRunResumeRequestDTO();
        resume.setRequestId(action.getRequestId());
        resume.setThreadId(String.valueOf(action.getChatId()));
        resume.setActionRequestId(action.getActionRequestId());
        resume.setActionEventId(execution.getActionEventId());
        resume.setResumeType(execution.getResumeType());
        resume.setActionOutcome(execution.getOutcome());
        AgentRunResponseDTO response;
        try {
            agentRunService.claimForResume(action.getOriginalRunId());
            response = customerAgentClient.resume(action.getAgentExecutionId(), resume);
        } catch (RuntimeException e) {
            response = actionFallback(execution);
        }
        LocalDateTime now = LocalDateTime.now();
        CustomerChatMessage assistant = new CustomerChatMessage()
                .setMessageId(redisIdWorker.nextId("chat_message"))
                .setImChatId(userMessage.getImChatId()).setChatId(userMessage.getChatId())
                .setUserId(userMessage.getUserId()).setSenderType(SENDER_ASSISTANT)
                .setMessageType(MESSAGE_TYPE_TEXT).setContent(response.getReply())
                .setStructuredContent(toJson(response.getStructuredContent()))
                .setReplyToMessageId(userMessage.getMessageId()).setCreateTime(now);
        chat.setLastActiveTime(now);
        agentRunService.completeResumed(action.getOriginalRunId(), response, assistant, chat,
                action.getStatus());
        updateImChatAfterAgent(imChat, response.getIntent(), now);
        if (execution.isRequiresHandoff() && autoHandoffEnabled) {
            handoffService.requestAgentHandoff(userMessage.getUserId(), userMessage.getImChatId(),
                    userMessage.getChatId(), "ACTION_STATE_CONFLICT",
                    action.getOriginalRunId(), action.getActionRequestId());
            imChat.setStatus(IM_CHAT_STATUS_HUMAN_PENDING);
            imChat.setHandlerType(HANDLER_TYPE_HUMAN);
        }
        ChatReplyDTO reply = toReply(userMessage, assistant, imChat);
        reply.setRunStatus("COMPLETED");
        reply.setActionRequestId(action.getActionRequestId());
        return reply;
    }

    private AgentRunResponseDTO actionFallback(CustomerActionService.ExecutionResult execution) {
        AgentRunResponseDTO response = new AgentRunResponseDTO();
        response.setRunId(execution.getAction().getAgentExecutionId());
        response.setReply(execution.getOutcome().getMessage());
        response.setIntent("ORDER_QUERY");
        response.setActiveAgent("transaction_agent");
        response.setGraphVersion("v4");
        response.setRunStatus("COMPLETED");
        response.setResolutionType("ACTION_PROPOSAL");
        response.setExecutionMode("SIMPLE");
        response.setOrchestrator("router");
        return response;
    }

    private ChatReplyDTO saveDeterministicReply(CustomerChatMessage userMessage,
                                                CustomerImChat imChat,
                                                CustomerChat chat,
                                                String content,
                                                String actionRequestId) {
        LocalDateTime now = LocalDateTime.now();
        messageMapper.insert(userMessage);
        CustomerChatMessage assistant = new CustomerChatMessage()
                .setMessageId(redisIdWorker.nextId("chat_message"))
                .setImChatId(userMessage.getImChatId()).setChatId(userMessage.getChatId())
                .setUserId(userMessage.getUserId()).setSenderType(SENDER_ASSISTANT)
                .setMessageType(MESSAGE_TYPE_TEXT).setContent(content)
                .setReplyToMessageId(userMessage.getMessageId()).setCreateTime(now);
        messageMapper.insert(assistant);
        updateConversationActivity(imChat, chat, userMessage.getContent(), now);
        ChatReplyDTO reply = toReply(userMessage, assistant, imChat);
        reply.setRunStatus("COMPLETED");
        reply.setActionRequestId(actionRequestId);
        return reply;
    }

    private boolean allowAgentHandoff(CustomerChatMessage userMessage, AgentRunResponseDTO response) {
        if (response.getHandoffProposal() == null || response.getHandoffProposal().getReasonCode() == null) {
            return false;
        }
        String reason = response.getHandoffProposal().getReasonCode();
        if ("USER_EXPLICIT_REQUEST".equals(reason)) {
            String text = userMessage.getContent();
            return text.contains("转人工") || text.contains("人工客服")
                    || text.contains("真人客服") || text.contains("找人工");
        }
        if (!autoHandoffEnabled) {
            return false;
        }
        if ("ALL_REQUIRED_TOOLS_FAILED_FINAL".equals(reason)) {
            return response.getTaskOutcomes() != null && !response.getTaskOutcomes().isEmpty()
                    && response.getTaskOutcomes().stream().noneMatch(item -> "SUCCEEDED".equals(item.getStatus()));
        }
        return false;
    }

    private List<AgentMessageDTO> loadRecentAgentMessages(CustomerChatMessage currentMessage) {
        List<CustomerChatMessage> messages = messageMapper.selectList(
                new QueryWrapper<CustomerChatMessage>()
                        .eq("chat_id", currentMessage.getChatId())
                        .eq("im_chat_id", currentMessage.getImChatId())
                        .eq("user_id", currentMessage.getUserId())
                        .orderByDesc("message_id")
                        .last("LIMIT 20"));
        Collections.reverse(messages);
        List<AgentMessageDTO> result = new ArrayList<>();
        for (CustomerChatMessage message : messages) {
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            result.add(new AgentMessageDTO(message.getMessageId(), roleOf(message.getSenderType()),
                    message.getContent()));
        }
        return result;
    }

    private String roleOf(String senderType) {
        if (SENDER_USER.equals(senderType)) {
            return "user";
        }
        if ("SYSTEM".equals(senderType)) {
            return "system";
        }
        return "assistant";
    }

    private void updateImChatAfterAgent(CustomerImChat imChat, String intent, LocalDateTime now) {
        if (intent != null && !intent.isBlank()
                && (imChat.getPrimaryIntent() == null || imChat.getPrimaryIntent().isBlank())) {
            imChat.setPrimaryIntent(intent);
        }
        imChat.setLastMessageTime(now);
        imChat.setUpdateTime(now);
        imChatMapper.updateById(imChat);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void updateConversationActivity(CustomerImChat imChat, CustomerChat chat,
                                            String firstMessageCandidate, LocalDateTime now) {
        if (DEFAULT_IM_CHAT_TITLE.equals(imChat.getTitle())) {
            imChat.setTitle(abbreviate(firstMessageCandidate, 30));
        }
        updateActivityOnly(imChat, chat, now);
    }

    private void updateActivityOnly(CustomerImChat imChat, CustomerChat chat, LocalDateTime now) {
        chat.setLastActiveTime(now);
        chatMapper.updateById(chat);
        imChat.setLastMessageTime(now);
        imChat.setUpdateTime(now);
        imChatMapper.updateById(imChat);
    }

    private boolean isExpired(CustomerChat chat, LocalDateTime now) {
        LocalDateTime lastActiveTime = chat.getLastActiveTime() == null
                ? chat.getStartTime() : chat.getLastActiveTime();
        return lastActiveTime != null && lastActiveTime.plusMinutes(CHAT_INACTIVE_MINUTES).isBefore(now);
    }

    private void validateSendRequest(ChatRequest request) {
        if (request == null) {
            throw new ChatBusinessException("请求不能为空");
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ChatBusinessException("消息不能为空");
        }
        if (request.getClientMessageId() == null || request.getClientMessageId().isBlank()) {
            throw new ChatBusinessException("clientMessageId不能为空");
        }
    }

    private int normalizePageNo(int pageNo) {
        return Math.max(pageNo, 1);
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_IM_CHAT_TITLE;
        }
        return abbreviate(title.trim(), 64);
    }

    private String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private boolean isHumanMode(CustomerImChat imChat) {
        return IM_CHAT_STATUS_HUMAN_PENDING.equals(imChat.getStatus())
                || IM_CHAT_STATUS_HUMAN_ACTIVE.equals(imChat.getStatus());
    }

    private ChatReplyDTO toReply(CustomerChatMessage userMessage,
                                 CustomerChatMessage assistantMessage,
                                 CustomerImChat imChat) {
        ChatReplyDTO reply = new ChatReplyDTO();
        reply.setImChatId(userMessage.getImChatId());
        reply.setChatId(userMessage.getChatId());
        reply.setUserMessageId(userMessage.getMessageId());
        if (assistantMessage != null) {
            reply.setAssistantMessageId(assistantMessage.getMessageId());
            reply.setReply(assistantMessage.getContent());
        }
        reply.setConversationStatus(imChat.getStatus());
        reply.setHandlerType(imChat.getHandlerType());
        return reply;
    }
}
