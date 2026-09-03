package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.ChatBusinessException;
import com.hmdp.dto.agent.AgentActionOutcomeDTO;
import com.hmdp.dto.agent.AgentActionProposalDTO;
import com.hmdp.dto.agent.AgentPendingActionDTO;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.dto.tool.BusinessReferenceDTO;
import com.hmdp.entity.CustomerActionEvent;
import com.hmdp.entity.CustomerActionRequest;
import com.hmdp.entity.CustomerAgentRun;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.CustomerActionEventMapper;
import com.hmdp.mapper.CustomerActionRequestMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CustomerActionService {
    public static final String CANCEL_UNPAID_ORDER = "CANCEL_UNPAID_ORDER";
    public static final String REQUEST_REFUND = "REQUEST_REFUND";
    public static final String AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION";
    public static final String EXECUTING = "EXECUTING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String DECLINED = "DECLINED";
    public static final String EXPIRED = "EXPIRED";
    public static final String HANDED_OFF = "HANDED_OFF";

    private static final Set<String> CONFIRM_PHRASES = Set.of(
            "确认", "确认执行", "确定执行", "继续执行", "是的，确认", "是的确认", "可以执行");
    private static final Set<String> DECLINE_PHRASES = Set.of(
            "不确认", "不要执行", "放弃操作", "暂不处理", "算了");

    private final CustomerActionRequestMapper requestMapper;
    private final CustomerActionEventMapper eventMapper;
    private final CustomerChatMessageMapper messageMapper;
    private final VoucherOrderMapper orderMapper;
    private final ObjectMapper objectMapper;
    private final boolean cancelEnabled;
    private final boolean refundEnabled;
    private final int confirmationTtlSeconds;

    public CustomerActionService(CustomerActionRequestMapper requestMapper,
                                 CustomerActionEventMapper eventMapper,
                                 CustomerChatMessageMapper messageMapper,
                                 VoucherOrderMapper orderMapper,
                                 ObjectMapper objectMapper,
                                 @Value("${customer-actions.cancel-enabled:false}") boolean cancelEnabled,
                                 @Value("${customer-actions.refund-enabled:false}") boolean refundEnabled,
                                 @Value("${customer-actions.confirmation-ttl-seconds:600}") int confirmationTtlSeconds) {
        this.requestMapper = requestMapper;
        this.eventMapper = eventMapper;
        this.messageMapper = messageMapper;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
        this.cancelEnabled = cancelEnabled;
        this.refundEnabled = refundEnabled;
        this.confirmationTtlSeconds = confirmationTtlSeconds;
    }

    public ConfirmationDecision classify(String message) {
        String normalized = normalize(message);
        if (CONFIRM_PHRASES.contains(normalized)) {
            return ConfirmationDecision.CONFIRM;
        }
        if (DECLINE_PHRASES.contains(normalized)) {
            return ConfirmationDecision.DECLINE;
        }
        return ConfirmationDecision.NONE;
    }

    @Transactional
    public CustomerActionRequest findActive(Long userId, Long imChatId) {
        CustomerActionRequest action = requestMapper.selectOne(new QueryWrapper<CustomerActionRequest>()
                .eq("user_id", userId)
                .eq("active_im_chat_id", imChatId)
                .last("LIMIT 1"));
        if (action != null && AWAITING_CONFIRMATION.equals(action.getStatus())
                && !action.getExpiresTime().isAfter(LocalDateTime.now())) {
            requestMapper.expire(action.getActionRequestId());
            return null;
        }
        return action;
    }

    public AgentPendingActionDTO toPendingContext(CustomerActionRequest action) {
        if (action == null) {
            return null;
        }
        return new AgentPendingActionDTO(action.getActionRequestId(), action.getActionType(),
                action.getTargetBizType(), action.getTargetBizId(), action.getExpiresTime().toString());
    }

    @Transactional
    public PreparationResult prepare(CustomerAgentRun run, CustomerChatMessage userMessage,
                                     AgentRunResponseDTO response) {
        AgentActionProposalDTO proposal = response.getActionProposal();
        if (proposal == null || proposal.getOrderId() == null || proposal.getActionType() == null) {
            return PreparationResult.rejected("ACTION_PROPOSAL_INVALID", false);
        }
        if (!isEnabled(proposal.getActionType())) {
            return PreparationResult.rejected("ACTION_DISABLED", false);
        }
        if (!explicitlyRequested(proposal.getActionType(), userMessage.getContent())
                || response.getBusinessRefs() == null
                || response.getBusinessRefs().stream().noneMatch(ref ->
                "VOUCHER_ORDER".equals(ref.getBizType()) && proposal.getOrderId().equals(ref.getBizId()))) {
            return PreparationResult.rejected("ACTION_NOT_GROUNDED", false);
        }
        CustomerActionRequest active = findActive(userMessage.getUserId(), userMessage.getImChatId());
        if (active != null) {
            return PreparationResult.existing(active);
        }
        VoucherOrder order = ownedOrder(proposal.getOrderId(), userMessage.getUserId());
        if (order == null) {
            return PreparationResult.rejected("ORDER_NOT_FOUND_OR_FORBIDDEN", false);
        }
        if (!eligible(proposal.getActionType(), order)) {
            return PreparationResult.rejected("ACTION_STATE_CONFLICT", true);
        }
        LocalDateTime now = LocalDateTime.now();
        CustomerActionRequest action = new CustomerActionRequest()
                .setActionRequestId(id())
                .setOriginalRunId(run.getRunId())
                .setAgentExecutionId(response.getRunId())
                .setRequestId(run.getRequestId())
                .setUserMessageId(userMessage.getMessageId())
                .setUserId(userMessage.getUserId())
                .setImChatId(userMessage.getImChatId())
                .setChatId(userMessage.getChatId())
                .setActionType(proposal.getActionType())
                .setTargetBizType("VOUCHER_ORDER")
                .setTargetBizId(order.getId())
                .setCanonicalParameters(json(Map.of("orderId", order.getId())))
                .setStatus(AWAITING_CONFIRMATION)
                .setActiveImChatId(userMessage.getImChatId())
                .setPolicyVersion("v4-1")
                .setExpiresTime(now.plusSeconds(confirmationTtlSeconds))
                .setCreateTime(now)
                .setUpdateTime(now);
        try {
            requestMapper.insert(action);
        } catch (DuplicateKeyException e) {
            CustomerActionRequest concurrent = findActive(userMessage.getUserId(), userMessage.getImChatId());
            if (concurrent != null) {
                return PreparationResult.existing(concurrent);
            }
            throw e;
        }
        saveEvent(action, null, "PROPOSED", null, AWAITING_CONFIRMATION, null);
        return PreparationResult.created(action);
    }

    @Transactional
    public ExecutionResult executeMessage(CustomerChatMessage userMessage,
                                          CustomerActionRequest action,
                                          ConfirmationDecision decision) {
        messageMapper.insert(userMessage);
        if (decision == ConfirmationDecision.DECLINE) {
            int updated = requestMapper.decline(action.getActionRequestId(), userMessage.getUserId());
            CustomerActionRequest current = requestMapper.selectById(action.getActionRequestId());
            if (updated == 1) {
                saveEvent(current, userMessage.getClientMessageId(), "DECLINED",
                        AWAITING_CONFIRMATION, DECLINED, null);
                return outcome(current, "DECLINED", "USER_DECLINED", "已放弃本次操作。", false);
            }
            if (current == null) {
                throw new ChatBusinessException("待确认操作不存在");
            }
            return outcomeFromExisting(current);
        }
        if (!action.getExpiresTime().isAfter(LocalDateTime.now())) {
            int updated = requestMapper.expire(action.getActionRequestId());
            CustomerActionRequest current = requestMapper.selectById(action.getActionRequestId());
            if (updated == 1) {
                saveEvent(current, userMessage.getClientMessageId(), "EXPIRED",
                        AWAITING_CONFIRMATION, EXPIRED, null);
                return outcome(current, "EXPIRED", "CONFIRMATION_EXPIRED",
                        "本次操作确认已过期，请重新发起。", false);
            }
            if (current == null) {
                throw new ChatBusinessException("待确认操作不存在");
            }
            return outcomeFromExisting(current);
        }
        if (requestMapper.claimForExecution(action.getActionRequestId(), userMessage.getUserId()) != 1) {
            CustomerActionRequest current = requestMapper.selectById(action.getActionRequestId());
            if (current == null) {
                throw new ChatBusinessException("待确认操作不存在");
            }
            return outcomeFromExisting(current);
        }
        CustomerActionRequest executing = requestMapper.selectById(action.getActionRequestId());
        VoucherOrder before = ownedOrder(executing.getTargetBizId(), userMessage.getUserId());
        if (before == null) {
            finish(executing, "FAILED_FINAL", "ORDER_NOT_FOUND_OR_FORBIDDEN", "订单不存在或无权操作");
            saveEvent(executing, userMessage.getClientMessageId(), "FAILED",
                    EXECUTING, "FAILED_FINAL", "ORDER_NOT_FOUND_OR_FORBIDDEN");
            return outcome(executing, "FAILED", "ORDER_NOT_FOUND_OR_FORBIDDEN",
                    "订单不存在或无权操作。", false);
        }
        int updated = CANCEL_UNPAID_ORDER.equals(executing.getActionType())
                ? orderMapper.cancelUnpaidForCustomer(before.getId(), userMessage.getUserId())
                : orderMapper.requestRefundForCustomer(before.getId(), userMessage.getUserId());
        if (updated == 1) {
            String code = CANCEL_UNPAID_ORDER.equals(executing.getActionType())
                    ? "ORDER_CANCELLED" : "REFUND_REQUESTED";
            String message = CANCEL_UNPAID_ORDER.equals(executing.getActionType())
                    ? "订单已取消。" : "退款申请已提交，订单当前为退款中，不代表资金已经到账。";
            finish(executing, SUCCEEDED, code, message);
            saveEvent(executing, userMessage.getClientMessageId(), "EXECUTED", EXECUTING, SUCCEEDED, code);
            return outcome(executing, "SUCCEEDED", code, message, false);
        }
        VoucherOrder latest = ownedOrder(executing.getTargetBizId(), userMessage.getUserId());
        if (alreadyCompleted(executing.getActionType(), latest)) {
            String message = CANCEL_UNPAID_ORDER.equals(executing.getActionType())
                    ? "该订单已经取消。" : "该订单已经在退款中或已退款。";
            finish(executing, SUCCEEDED, "ALREADY_COMPLETED", message);
            saveEvent(executing, userMessage.getClientMessageId(), "IDEMPOTENT_RESULT",
                    EXECUTING, SUCCEEDED, "ALREADY_COMPLETED");
            return outcome(executing, "SUCCEEDED", "ALREADY_COMPLETED", message, false);
        }
        finish(executing, HANDED_OFF, "ACTION_STATE_CONFLICT", "订单状态已变化，需要人工处理");
        saveEvent(executing, userMessage.getClientMessageId(), "HANDED_OFF",
                EXECUTING, HANDED_OFF, "ACTION_STATE_CONFLICT");
        return outcome(executing, "HANDED_OFF", "ACTION_STATE_CONFLICT",
                "订单状态已变化，我将为你转接人工客服继续处理。", true);
    }

    public CustomerActionEvent findEventByClientMessageId(String clientMessageId) {
        return eventMapper.selectOne(new QueryWrapper<CustomerActionEvent>()
                .eq("client_message_id", clientMessageId).last("LIMIT 1"));
    }

    public CustomerActionRequest findById(String actionRequestId) {
        return requestMapper.selectById(actionRequestId);
    }

    public CustomerActionRequest findByProposalUserMessageId(Long userMessageId) {
        return requestMapper.selectOne(new QueryWrapper<CustomerActionRequest>()
                .eq("user_message_id", userMessageId).last("LIMIT 1"));
    }

    public ExecutionResult recoverExecutionResult(String clientMessageId) {
        CustomerActionEvent event = findEventByClientMessageId(clientMessageId);
        if (event == null) {
            return null;
        }
        CustomerActionRequest action = requestMapper.selectById(event.getActionRequestId());
        return action == null ? null : outcomeFromExisting(action);
    }

    private ExecutionResult outcomeFromExisting(CustomerActionRequest action) {
        if (EXECUTING.equals(action.getStatus())) {
            throw new ChatBusinessException("该操作正在处理中，请稍后重试");
        }
        String message = action.getResultPayload() == null ? "本次操作已处理。" : action.getResultPayload();
        String status = SUCCEEDED.equals(action.getStatus()) ? "SUCCEEDED" : action.getStatus();
        return outcome(action, status, action.getResultCode(), message, HANDED_OFF.equals(action.getStatus()));
    }

    private ExecutionResult outcome(CustomerActionRequest action, String status, String code,
                                    String message, boolean requiresHandoff) {
        AgentActionOutcomeDTO dto = new AgentActionOutcomeDTO()
                .setStatus(status)
                .setActionType(action.getActionType())
                .setTargetBizType(action.getTargetBizType())
                .setTargetBizId(action.getTargetBizId())
                .setResultCode(code == null ? "ACTION_PROCESSED" : code)
                .setMessage(message)
                .setBusinessRefs(Collections.singletonList(
                        new BusinessReferenceDTO("VOUCHER_ORDER", action.getTargetBizId())));
        String resumeType;
        if ("DECLINED".equals(status)) resumeType = "DECLINED";
        else if ("EXPIRED".equals(status)) resumeType = "EXPIRED";
        else if ("HANDED_OFF".equals(status)) resumeType = "HANDED_OFF";
        else if ("SUCCEEDED".equals(status)) resumeType = "EXECUTION_SUCCEEDED";
        else resumeType = "EXECUTION_FAILED";
        CustomerActionEvent event = latestEvent(action.getActionRequestId());
        return new ExecutionResult(action, event == null ? id() : event.getEventId(),
                resumeType, dto, requiresHandoff);
    }

    private CustomerActionEvent latestEvent(String actionRequestId) {
        return eventMapper.selectOne(new QueryWrapper<CustomerActionEvent>()
                .eq("action_request_id", actionRequestId)
                .orderByDesc("create_time").last("LIMIT 1"));
    }

    private void finish(CustomerActionRequest action, String status, String code, String message) {
        action.setStatus(status).setActiveImChatId(null).setResultCode(code)
                .setResultPayload(message).setExecutedTime(LocalDateTime.now()).setUpdateTime(LocalDateTime.now());
        String errorCode = status.startsWith("FAILED") ? code : null;
        action.setErrorCode(errorCode);
        requestMapper.finish(action.getActionRequestId(), status, code, message, errorCode);
    }

    private void saveEvent(CustomerActionRequest action, String clientMessageId, String type,
                           String from, String to, String payload) {
        eventMapper.insert(new CustomerActionEvent().setEventId(id())
                .setActionRequestId(action.getActionRequestId()).setClientMessageId(clientMessageId)
                .setEventType(type).setFromStatus(from).setToStatus(to).setPayload(payload)
                .setCreateTime(LocalDateTime.now()));
    }

    private VoucherOrder ownedOrder(Long orderId, Long userId) {
        return orderMapper.selectOne(new QueryWrapper<VoucherOrder>()
                .eq("id", orderId).eq("user_id", userId).last("LIMIT 1"));
    }

    private boolean eligible(String actionType, VoucherOrder order) {
        if (CANCEL_UNPAID_ORDER.equals(actionType)) return Integer.valueOf(1).equals(order.getStatus());
        return REQUEST_REFUND.equals(actionType) && Integer.valueOf(2).equals(order.getStatus())
                && order.getUseTime() == null;
    }

    private boolean alreadyCompleted(String actionType, VoucherOrder order) {
        if (order == null) return false;
        if (CANCEL_UNPAID_ORDER.equals(actionType)) return Integer.valueOf(4).equals(order.getStatus());
        return Integer.valueOf(5).equals(order.getStatus()) || Integer.valueOf(6).equals(order.getStatus());
    }

    private boolean isEnabled(String actionType) {
        return CANCEL_UNPAID_ORDER.equals(actionType) ? cancelEnabled
                : REQUEST_REFUND.equals(actionType) && refundEnabled;
    }

    private boolean explicitlyRequested(String actionType, String message) {
        if (message == null) return false;
        if (CANCEL_UNPAID_ORDER.equals(actionType)) {
            return message.contains("取消") && message.contains("订单");
        }
        return REQUEST_REFUND.equals(actionType) && message.contains("退款");
    }

    private String normalize(String message) {
        if (message == null) return "";
        return message.trim().replaceAll("[\\p{P}\\s]+$", "").replaceAll("\\s+", "");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化客服动作参数", e);
        }
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public enum ConfirmationDecision { CONFIRM, DECLINE, NONE }

    @lombok.Value
    public static class PreparationResult {
        CustomerActionRequest action;
        boolean created;
        boolean requiresHandoff;
        String errorCode;

        static PreparationResult created(CustomerActionRequest action) {
            return new PreparationResult(action, true, false, null);
        }
        static PreparationResult existing(CustomerActionRequest action) {
            return new PreparationResult(action, false, false, null);
        }
        static PreparationResult rejected(String errorCode, boolean requiresHandoff) {
            return new PreparationResult(null, false, requiresHandoff, errorCode);
        }
    }

    @lombok.Value
    public static class ExecutionResult {
        CustomerActionRequest action;
        String actionEventId;
        String resumeType;
        AgentActionOutcomeDTO outcome;
        boolean requiresHandoff;
    }
}
