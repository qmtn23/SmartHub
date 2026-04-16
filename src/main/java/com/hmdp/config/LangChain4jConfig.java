package com.hmdp.config;

import com.hmdp.service.CustomerAssistant;
import com.hmdp.utils.CustomerServiceTools;
import com.hmdp.utils.RedisChatMemoryStore;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
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
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
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

    @Bean("businessEmbeddingStore")
    public EmbeddingStore<TextSegment> businessEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean("skillEmbeddingStore")
    public EmbeddingStore<TextSegment> skillEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public EmbeddingStoreIngestor embeddingStoreIngestor(EmbeddingModel embeddingModel,
                                                         @Qualifier("businessEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore) {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(300, 50))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    @Bean
    public ContentRetriever businessContentRetriever(EmbeddingModel embeddingModel,
                                                      @Qualifier("businessEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.6)
                .build();
    }

    @Bean
    public ContentRetriever skillContentRetriever(EmbeddingModel embeddingModel,
                                                   @Qualifier("skillEmbeddingStore") EmbeddingStore<TextSegment> skillEmbeddingStore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(skillEmbeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(2)
                .minScore(0.5)
                .build();
    }

    @Bean
    public RetrievalAugmentor retrievalAugmentor(ContentRetriever businessContentRetriever,
                                                  ContentRetriever skillContentRetriever) {
        QueryRouter queryRouter = new DefaultQueryRouter(businessContentRetriever, skillContentRetriever);
        return DefaultRetrievalAugmentor.builder()
                .queryRouter(queryRouter)
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
