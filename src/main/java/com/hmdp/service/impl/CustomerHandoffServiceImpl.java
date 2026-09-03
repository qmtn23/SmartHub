package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.ChatBusinessException;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatBizRef;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerHandoff;
import com.hmdp.entity.CustomerImChat;
import com.hmdp.mapper.CustomerChatBizRefMapper;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.CustomerHandoffMapper;
import com.hmdp.mapper.CustomerImChatMapper;
import com.hmdp.service.IConversationMemoryService;
import com.hmdp.service.ICustomerHandoffService;
import com.hmdp.utils.RedisIdWorker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.CustomerChatConstants.*;

@Service
public class CustomerHandoffServiceImpl implements ICustomerHandoffService {

    private final CustomerHandoffMapper handoffMapper;
    private final CustomerImChatMapper imChatMapper;
    private final CustomerChatMapper chatMapper;
    private final CustomerChatMessageMapper messageMapper;
    private final CustomerChatBizRefMapper bizRefMapper;
    private final IConversationMemoryService conversationMemoryService;
    private final RedisIdWorker redisIdWorker;

    public CustomerHandoffServiceImpl(CustomerHandoffMapper handoffMapper,
                                      CustomerImChatMapper imChatMapper,
                                      CustomerChatMapper chatMapper,
                                      CustomerChatMessageMapper messageMapper,
                                      CustomerChatBizRefMapper bizRefMapper,
                                      IConversationMemoryService conversationMemoryService,
                                      RedisIdWorker redisIdWorker) {
        this.handoffMapper = handoffMapper;
        this.imChatMapper = imChatMapper;
        this.chatMapper = chatMapper;
        this.messageMapper = messageMapper;
        this.bizRefMapper = bizRefMapper;
        this.conversationMemoryService = conversationMemoryService;
        this.redisIdWorker = redisIdWorker;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerHandoff requestHandoff(Long userId, Long imChatId, Long chatId, String reason) {
        CustomerImChat imChat = requireOwnedImChat(userId, imChatId);
        CustomerHandoff existing = findActiveHandoff(imChatId);
        if (existing != null) {
            return existing;
        }
        if (!IM_CHAT_STATUS_BOT_ACTIVE.equals(imChat.getStatus())) {
            throw new ChatBusinessException("当前长会话不能发起新的转人工请求");
        }

        CustomerChat botChat = requireActiveChat(userId, imChatId, chatId);
        LocalDateTime now = LocalDateTime.now();
        conversationMemoryService.finalizeChat(imChat, botChat, now);

        CustomerChat humanChat = new CustomerChat()
                .setChatId(redisIdWorker.nextId("chat"))
                .setImChatId(imChatId)
                .setUserId(userId)
                .setStatus(CHAT_STATUS_ACTIVE)
                .setStartTime(now)
                .setLastActiveTime(now);
        chatMapper.insert(humanChat);

        CustomerHandoff handoff = new CustomerHandoff()
                .setHandoffId(redisIdWorker.nextId("handoff"))
                .setImChatId(imChatId)
                .setFromChatId(chatId)
                .setHumanChatId(humanChat.getChatId())
                .setUserId(userId)
                .setReason(normalizeReason(reason))
                .setReasonCode(isAgentReasonCode(reason) ? reason : null)
                .setSource(isAgentReasonCode(reason) ? "AGENT_POLICY" : "USER")
                .setSummary(normalizeSummary(imChat.getSummary()))
                .setBusinessRefs(businessReferenceSnapshot(userId, imChatId))
                .setStatus(HANDOFF_STATUS_PENDING)
                .setRequestedTime(now)
                .setUpdateTime(now);
        handoffMapper.insert(handoff);

        imChat.setHandlerType(HANDLER_TYPE_HUMAN);
        imChat.setStatus(IM_CHAT_STATUS_HUMAN_PENDING);
        imChat.setUpdateTime(now);
        imChatMapper.updateById(imChat);
        return handoff;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerHandoff requestAgentHandoff(Long userId, Long imChatId, Long chatId, String reason,
                                               String agentRunId, String actionRequestId) {
        CustomerHandoff handoff = requestHandoff(userId, imChatId, chatId, reason);
        handoff.setAgentRunId(agentRunId).setActionRequestId(actionRequestId)
                .setReasonCode(reason)
                .setSource("USER_EXPLICIT_REQUEST".equals(reason) ? "USER" : "AGENT_POLICY")
                .setUpdateTime(LocalDateTime.now());
        handoffMapper.updateById(handoff);
        return handoff;
    }

    @Override
    public CustomerHandoff getCurrentHandoff(Long userId, Long imChatId) {
        requireOwnedImChat(userId, imChatId);
        CustomerHandoff handoff = handoffMapper.selectOne(new QueryWrapper<CustomerHandoff>()
                .eq("im_chat_id", imChatId)
                .eq("user_id", userId)
                .orderByDesc("handoff_id")
                .last("LIMIT 1"));
        if (handoff == null) {
            throw new ChatBusinessException("该长会话暂无转人工记录");
        }
        return handoff;
    }

    @Override
    public IPage<CustomerHandoff> listPending(int pageNo, int pageSize) {
        Page<CustomerHandoff> page = new Page<>(Math.max(1, pageNo), Math.min(100, Math.max(1, pageSize)));
        return handoffMapper.selectPage(page, new QueryWrapper<CustomerHandoff>()
                .eq("status", HANDOFF_STATUS_PENDING)
                .orderByAsc("requested_time"));
    }

    @Override
    public IPage<CustomerChatMessage> listHumanMessages(Long handoffId, String operatorId,
                                                         int pageNo, int pageSize) {
        CustomerHandoff handoff = requireAcceptedHandoff(handoffId, operatorId);
        Page<CustomerChatMessage> page = new Page<>(
                Math.max(1, pageNo), Math.min(100, Math.max(1, pageSize)));
        return messageMapper.selectPage(page, new QueryWrapper<CustomerChatMessage>()
                .eq("im_chat_id", handoff.getImChatId())
                .eq("chat_id", handoff.getHumanChatId())
                .eq("user_id", handoff.getUserId())
                .orderByDesc("message_id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerHandoff accept(Long handoffId, String operatorId) {
        String normalizedOperatorId = requireOperatorId(operatorId);
        CustomerHandoff handoff = requireHandoff(handoffId);
        if (HANDOFF_STATUS_ACCEPTED.equals(handoff.getStatus())) {
            ensureSameOperator(handoff, normalizedOperatorId);
            return handoff;
        }
        if (!HANDOFF_STATUS_PENDING.equals(handoff.getStatus())) {
            throw new ChatBusinessException("该转人工请求已结束，不能接入");
        }

        LocalDateTime now = LocalDateTime.now();
        handoff.setStatus(HANDOFF_STATUS_ACCEPTED);
        handoff.setOperatorId(normalizedOperatorId);
        handoff.setAcceptedTime(now);
        handoff.setUpdateTime(now);
        int updated = handoffMapper.update(handoff, new UpdateWrapper<CustomerHandoff>()
                .eq("handoff_id", handoffId)
                .eq("status", HANDOFF_STATUS_PENDING));
        if (updated == 0) {
            throw new ChatBusinessException("该转人工请求已被其他客服接入");
        }

        CustomerImChat imChat = requireImChat(handoff.getImChatId());
        imChat.setStatus(IM_CHAT_STATUS_HUMAN_ACTIVE);
        imChat.setHandlerType(HANDLER_TYPE_HUMAN);
        imChat.setUpdateTime(now);
        imChatMapper.updateById(imChat);
        return handoff;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerChatMessage sendHumanMessage(Long handoffId, String operatorId, String content) {
        CustomerHandoff handoff = requireAcceptedHandoff(handoffId, operatorId);
        String normalizedContent = requireContent(content);
        CustomerChat humanChat = requireActiveChat(
                handoff.getUserId(), handoff.getImChatId(), handoff.getHumanChatId());
        LocalDateTime now = LocalDateTime.now();
        CustomerChatMessage message = new CustomerChatMessage()
                .setMessageId(redisIdWorker.nextId("chat_message"))
                .setImChatId(handoff.getImChatId())
                .setChatId(handoff.getHumanChatId())
                .setUserId(handoff.getUserId())
                .setSenderType(SENDER_HUMAN)
                .setMessageType(MESSAGE_TYPE_TEXT)
                .setContent(normalizedContent)
                .setCreateTime(now);
        messageMapper.insert(message);

        humanChat.setLastActiveTime(now);
        chatMapper.updateById(humanChat);
        CustomerImChat imChat = requireImChat(handoff.getImChatId());
        imChat.setLastMessageTime(now);
        imChat.setUpdateTime(now);
        imChatMapper.updateById(imChat);
        return message;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerHandoff complete(Long handoffId, String operatorId) {
        CustomerHandoff handoff = requireAcceptedHandoff(handoffId, operatorId);
        CustomerImChat imChat = requireImChat(handoff.getImChatId());
        CustomerChat humanChat = requireActiveChat(
                handoff.getUserId(), handoff.getImChatId(), handoff.getHumanChatId());
        LocalDateTime now = LocalDateTime.now();
        conversationMemoryService.finalizeChat(imChat, humanChat, now);

        handoff.setStatus(HANDOFF_STATUS_COMPLETED);
        handoff.setCompletedTime(now);
        handoff.setUpdateTime(now);
        handoffMapper.updateById(handoff);

        imChat.setStatus(IM_CHAT_STATUS_BOT_ACTIVE);
        imChat.setHandlerType(HANDLER_TYPE_BOT);
        imChat.setUpdateTime(now);
        imChatMapper.updateById(imChat);
        return handoff;
    }

    private CustomerHandoff findActiveHandoff(Long imChatId) {
        return handoffMapper.selectOne(new QueryWrapper<CustomerHandoff>()
                .eq("im_chat_id", imChatId)
                .in("status", HANDOFF_STATUS_PENDING, HANDOFF_STATUS_ACCEPTED)
                .orderByDesc("handoff_id")
                .last("LIMIT 1"));
    }

    private CustomerImChat requireOwnedImChat(Long userId, Long imChatId) {
        if (imChatId == null) {
            throw new ChatBusinessException("imChatId不能为空");
        }
        CustomerImChat imChat = imChatMapper.selectOne(new QueryWrapper<CustomerImChat>()
                .eq("im_chat_id", imChatId)
                .eq("user_id", userId));
        if (imChat == null) {
            throw new ChatBusinessException("长会话不存在或无权访问");
        }
        return imChat;
    }

    private CustomerImChat requireImChat(Long imChatId) {
        CustomerImChat imChat = imChatMapper.selectById(imChatId);
        if (imChat == null) {
            throw new ChatBusinessException("长会话不存在");
        }
        return imChat;
    }

    private CustomerChat requireActiveChat(Long userId, Long imChatId, Long chatId) {
        if (chatId == null) {
            throw new ChatBusinessException("chatId不能为空");
        }
        CustomerChat chat = chatMapper.selectOne(new QueryWrapper<CustomerChat>()
                .eq("chat_id", chatId)
                .eq("im_chat_id", imChatId)
                .eq("user_id", userId)
                .eq("status", CHAT_STATUS_ACTIVE));
        if (chat == null) {
            throw new ChatBusinessException("短会话不存在、已结束或无权访问");
        }
        return chat;
    }

    private CustomerHandoff requireHandoff(Long handoffId) {
        if (handoffId == null) {
            throw new ChatBusinessException("handoffId不能为空");
        }
        CustomerHandoff handoff = handoffMapper.selectById(handoffId);
        if (handoff == null) {
            throw new ChatBusinessException("转人工记录不存在");
        }
        return handoff;
    }

    private CustomerHandoff requireAcceptedHandoff(Long handoffId, String operatorId) {
        String normalizedOperatorId = requireOperatorId(operatorId);
        CustomerHandoff handoff = requireHandoff(handoffId);
        if (!HANDOFF_STATUS_ACCEPTED.equals(handoff.getStatus())) {
            throw new ChatBusinessException("人工客服尚未接入或接待已经结束");
        }
        ensureSameOperator(handoff, normalizedOperatorId);
        return handoff;
    }

    private void ensureSameOperator(CustomerHandoff handoff, String operatorId) {
        if (!operatorId.equals(handoff.getOperatorId())) {
            throw new ChatBusinessException("该会话已由其他人工客服接入");
        }
    }

    private String businessReferenceSnapshot(Long userId, Long imChatId) {
        List<CustomerChatBizRef> refs = bizRefMapper.selectList(new QueryWrapper<CustomerChatBizRef>()
                .eq("user_id", userId)
                .eq("im_chat_id", imChatId)
                .orderByAsc("create_time"));
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        return refs.stream()
                .map(ref -> ref.getBizType() + ":" + ref.getBizId())
                .collect(Collectors.joining(","));
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "用户申请转人工";
        }
        String normalized = reason.trim();
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private boolean isAgentReasonCode(String reason) {
        return "USER_EXPLICIT_REQUEST".equals(reason)
                || "POLICY_REQUIRES_HUMAN".equals(reason)
                || "REFUND_INELIGIBLE_REQUIRES_REVIEW".equals(reason)
                || "ACTION_STATE_CONFLICT".equals(reason)
                || "ALL_REQUIRED_TOOLS_FAILED_FINAL".equals(reason);
    }

    private String normalizeSummary(String summary) {
        return summary == null || summary.isBlank() ? "暂无已生成的会话摘要" : summary.trim();
    }

    private String requireOperatorId(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new ChatBusinessException("operatorId不能为空");
        }
        String normalized = operatorId.trim();
        if (normalized.length() > 64) {
            throw new ChatBusinessException("operatorId长度不能超过64个字符");
        }
        return normalized;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new ChatBusinessException("人工客服消息不能为空");
        }
        String normalized = content.trim();
        if (normalized.length() > 2000) {
            throw new ChatBusinessException("人工客服消息不能超过2000个字符");
        }
        return normalized;
    }
}
