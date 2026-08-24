package com.hmdp.config;

import com.hmdp.service.CustomerAssistant;
import com.hmdp.utils.CustomerServiceTools;
import com.hmdp.utils.RedisChatMemoryStore;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class LangChain4jConfig {

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.chat-model}")
    private String chatModelName;

    @Value("${dashscope.embedding-model}")
    private String embeddingModelName;

    @Value("${tavily.api-key}")
    private String tavilyApiKey;

    @Bean
    public ChatModel qwenChatModel() {
        return QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(chatModelName)
                .build();
    }

    @Bean
    public EmbeddingModel qwenEmbeddingModel() {
        return QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingModelName)
                .build();
    }

    /**
     * 只存放FAQ、平台规则和客服SOP等静态知识。
     * 店铺、订单、优惠券和笔记等动态数据必须通过工具网关实时查询。
     */
    @Bean("staticKnowledgeEmbeddingStore")
    public EmbeddingStore<TextSegment> staticKnowledgeEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public ContentRetriever staticKnowledgeContentRetriever(
            EmbeddingModel embeddingModel,
            @Qualifier("staticKnowledgeEmbeddingStore") EmbeddingStore<TextSegment> staticKnowledgeEmbeddingStore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(staticKnowledgeEmbeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(4)
                .minScore(0.55)
                .build();
    }

    @Bean
    public RetrievalAugmentor retrievalAugmentor(ContentRetriever staticKnowledgeContentRetriever) {
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(staticKnowledgeContentRetriever)
                .build();
    }

    @Bean(destroyMethod = "close")
    public McpClient tavilyMcpClient() {
        String mcpUrl = "https://mcp.tavily.com/mcp/?tavilyApiKey=" + tavilyApiKey;
        log.info("正在连接 Tavily MCP Server: {}", mcpUrl.replaceAll("tavilyApiKey=.*", "tavilyApiKey=***"));
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url(mcpUrl)
                .logRequests(true)
                .logResponses(true)
                .build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
    }

    @Bean
    public ToolProvider mcpToolProvider(McpClient tavilyMcpClient) {
        return McpToolProvider.builder()
                .mcpClients(tavilyMcpClient)
                .build();
    }

    @Bean
    public CustomerAssistant customerAssistant(ChatModel chatModel,
                                                RetrievalAugmentor retrievalAugmentor,
                                                CustomerServiceTools tools,
                                                ToolProvider mcpToolProvider,
                                                RedisChatMemoryStore memoryStore) {
        return AiServices.builder(CustomerAssistant.class)
                .chatModel(chatModel)
                .retrievalAugmentor(retrievalAugmentor)
                .tools(tools)
                .toolProvider(mcpToolProvider)
                .chatMemoryProvider(userId ->
                        MessageWindowChatMemory.builder()
                                .id(userId)
                                .maxMessages(20)
                                .chatMemoryStore(memoryStore)
                                .build())
                .build();
    }
}
