package com.hmdp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.dto.ChatMessageDTO;
import com.hmdp.dto.ChatRequest;
import com.hmdp.dto.ChatSessionDTO;
import com.hmdp.dto.CreateImChatRequest;
import com.hmdp.dto.ImChatDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerImChat;
import com.hmdp.service.ICustomerChatService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ICustomerChatService customerChatService;

    public ChatController(ICustomerChatService customerChatService) {
        this.customerChatService = customerChatService;
    }

    /**
     * 创建长会话，并同时创建它的第一个短会话。
     */
    @PostMapping("/im-chats")
    public Result createImChat(@RequestBody(required = false) CreateImChatRequest request) {
        Long userId = currentUserId();
        String title = request == null ? null : request.getTitle();
        CustomerImChat imChat = customerChatService.createImChat(userId, title);
        CustomerChat chat = customerChatService.createOrResumeChat(userId, imChat.getImChatId());
        Map<String, Object> data = new HashMap<>();
        data.put("imChat", toImChatDTO(imChat));
        data.put("chat", toChatSessionDTO(chat));
        return Result.ok(data);
    }

    /**
     * 查询当前用户的长会话列表。
     */
    @GetMapping("/im-chats")
    public Result listImChats(@RequestParam(defaultValue = "1") int pageNo,
                              @RequestParam(defaultValue = "20") int pageSize) {
        IPage<CustomerImChat> page = customerChatService.listImChats(currentUserId(), pageNo, pageSize);
        List<ImChatDTO> records = page.getRecords().stream()
                .map(this::toImChatDTO)
                .collect(Collectors.toList());
        return Result.ok(records, page.getTotal());
    }

    /**
     * 查询一个长会话的基本信息。
     */
    @GetMapping("/im-chats/{imChatId}")
    public Result getImChat(@PathVariable Long imChatId) {
        return Result.ok(toImChatDTO(customerChatService.getImChat(currentUserId(), imChatId)));
    }

    /**
     * 进入长会话。未超时的短会话会继续使用，否则创建新的chatId。
     */
    @PostMapping("/im-chats/{imChatId}/chats")
    public Result createOrResumeChat(@PathVariable Long imChatId) {
        CustomerChat chat = customerChatService.createOrResumeChat(currentUserId(), imChatId);
        return Result.ok(toChatSessionDTO(chat));
    }

    /**
     * 在指定短会话中发送消息。
     */
    @PostMapping("/send")
    public Result sendMessage(@RequestBody ChatRequest request) {
        return Result.ok(customerChatService.sendMessage(currentUserId(), request));
    }

    /**
     * 按长会话分页查询消息，结果按新到旧排列。
     */
    @GetMapping("/history")
    public Result getHistory(@RequestParam Long imChatId,
                             @RequestParam(defaultValue = "1") int pageNo,
                             @RequestParam(defaultValue = "50") int pageSize) {
        IPage<CustomerChatMessage> page = customerChatService.listMessages(
                currentUserId(), imChatId, pageNo, pageSize);
        List<ChatMessageDTO> records = page.getRecords().stream()
                .map(this::toChatMessageDTO)
                .collect(Collectors.toList());
        return Result.ok(records, page.getTotal());
    }

    /**
     * 关闭长会话及其仍活跃的短会话。
     */
    @PostMapping("/im-chats/{imChatId}/close")
    public Result closeImChat(@PathVariable Long imChatId) {
        customerChatService.closeImChat(currentUserId(), imChatId);
        return Result.ok();
    }

    private Long currentUserId() {
        return UserHolder.getUser().getId();
    }

    private ImChatDTO toImChatDTO(CustomerImChat source) {
        ImChatDTO target = new ImChatDTO();
        target.setImChatId(source.getImChatId());
        target.setTitle(source.getTitle());
        target.setPrimaryIntent(source.getPrimaryIntent());
        target.setSummary(source.getSummary());
        target.setHandlerType(source.getHandlerType());
        target.setStatus(source.getStatus());
        target.setLastMessageTime(source.getLastMessageTime());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        target.setCloseTime(source.getCloseTime());
        return target;
    }

    private ChatSessionDTO toChatSessionDTO(CustomerChat source) {
        ChatSessionDTO target = new ChatSessionDTO();
        target.setChatId(source.getChatId());
        target.setImChatId(source.getImChatId());
        target.setIntent(source.getIntent());
        target.setSummary(source.getSummary());
        target.setStatus(source.getStatus());
        target.setStartTime(source.getStartTime());
        target.setLastActiveTime(source.getLastActiveTime());
        target.setEndTime(source.getEndTime());
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
        target.setStructuredContent(source.getStructuredContent());
        target.setReplyToMessageId(source.getReplyToMessageId());
        target.setCreateTime(source.getCreateTime());
        return target;
    }
}
