package com.hmdp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.dto.ChatMessageDTO;
import com.hmdp.dto.HandoffDTO;
import com.hmdp.dto.HumanMessageRequest;
import com.hmdp.dto.HumanOperatorRequest;
import com.hmdp.dto.Result;
import com.hmdp.config.ChatBusinessException;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerHandoff;
import com.hmdp.service.ICustomerHandoffService;
import com.hmdp.service.OperatorAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/chat/human")
public class HumanChatController {

    private static final String OPERATOR_KEY_HEADER = "X-Customer-Service-Key";

    private final ICustomerHandoffService handoffService;
    private final OperatorAuthService operatorAuthService;

    public HumanChatController(ICustomerHandoffService handoffService,
                               OperatorAuthService operatorAuthService) {
        this.handoffService = handoffService;
        this.operatorAuthService = operatorAuthService;
    }

    @GetMapping("/pending")
    public Result listPending(
            @RequestHeader(value = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        operatorAuthService.verify(operatorKey);
        IPage<CustomerHandoff> page = handoffService.listPending(pageNo, pageSize);
        List<HandoffDTO> records = page.getRecords().stream()
                .map(this::toHandoffDTO)
                .collect(Collectors.toList());
        return Result.ok(records, page.getTotal());
    }

    @PostMapping("/handoffs/{handoffId}/accept")
    public Result accept(@RequestHeader(value = OPERATOR_KEY_HEADER, required = false) String operatorKey,
                         @PathVariable Long handoffId,
                         @RequestBody HumanOperatorRequest request) {
        operatorAuthService.verify(operatorKey);
        return Result.ok(toHandoffDTO(handoffService.accept(handoffId, operatorIdOf(request))));
    }

    @GetMapping("/handoffs/{handoffId}/messages")
    public Result listMessages(
            @RequestHeader(value = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @PathVariable Long handoffId,
            @RequestParam String operatorId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "50") int pageSize) {
        operatorAuthService.verify(operatorKey);
        IPage<CustomerChatMessage> page = handoffService.listHumanMessages(
                handoffId, operatorId, pageNo, pageSize);
        List<ChatMessageDTO> records = page.getRecords().stream()
                .map(this::toChatMessageDTO)
                .collect(Collectors.toList());
        return Result.ok(records, page.getTotal());
    }

    @PostMapping("/handoffs/{handoffId}/messages")
    public Result sendMessage(@RequestHeader(value = OPERATOR_KEY_HEADER, required = false) String operatorKey,
                              @PathVariable Long handoffId,
                              @RequestBody HumanMessageRequest request) {
        operatorAuthService.verify(operatorKey);
        if (request == null) {
            throw new ChatBusinessException("人工客服消息请求不能为空");
        }
        CustomerChatMessage message = handoffService.sendHumanMessage(
                handoffId, request.getOperatorId(), request.getContent());
        return Result.ok(toChatMessageDTO(message));
    }

    @PostMapping("/handoffs/{handoffId}/complete")
    public Result complete(@RequestHeader(value = OPERATOR_KEY_HEADER, required = false) String operatorKey,
                           @PathVariable Long handoffId,
                           @RequestBody HumanOperatorRequest request) {
        operatorAuthService.verify(operatorKey);
        return Result.ok(toHandoffDTO(handoffService.complete(handoffId, operatorIdOf(request))));
    }

    private HandoffDTO toHandoffDTO(CustomerHandoff source) {
        HandoffDTO target = new HandoffDTO();
        target.setHandoffId(source.getHandoffId());
        target.setImChatId(source.getImChatId());
        target.setFromChatId(source.getFromChatId());
        target.setHumanChatId(source.getHumanChatId());
        target.setReason(source.getReason());
        target.setSummary(source.getSummary());
        target.setBusinessRefs(source.getBusinessRefs());
        target.setStatus(source.getStatus());
        target.setOperatorId(source.getOperatorId());
        target.setRequestedTime(source.getRequestedTime());
        target.setAcceptedTime(source.getAcceptedTime());
        target.setCompletedTime(source.getCompletedTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    private ChatMessageDTO toChatMessageDTO(CustomerChatMessage source) {
        ChatMessageDTO target = new ChatMessageDTO();
        target.setMessageId(source.getMessageId());
        target.setImChatId(source.getImChatId());
        target.setChatId(source.getChatId());
        target.setSenderType(source.getSenderType());
        target.setMessageType(source.getMessageType());
        target.setContent(source.getContent());
        target.setCreateTime(source.getCreateTime());
        return target;
    }

    private String operatorIdOf(HumanOperatorRequest request) {
        if (request == null) {
            throw new ChatBusinessException("人工客服操作请求不能为空");
        }
        return request.getOperatorId();
    }
}
