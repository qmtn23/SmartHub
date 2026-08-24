package com.hmdp.utils;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 仅加载版本化的静态客服知识，不读取店铺、订单、优惠券或笔记业务表。
 */
@Slf4j
@Component
public class StaticKnowledgeInitializer implements ApplicationRunner {

    private final EmbeddingStore<TextSegment> staticKnowledgeEmbeddingStore;
    private final EmbeddingModel embeddingModel;

    public StaticKnowledgeInitializer(
            @Qualifier("staticKnowledgeEmbeddingStore")
            EmbeddingStore<TextSegment> staticKnowledgeEmbeddingStore,
            EmbeddingModel embeddingModel) {
        this.staticKnowledgeEmbeddingStore = staticKnowledgeEmbeddingStore;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始加载静态客服知识文档...");
        int documentCount = 0;
        int segmentCount = 0;

        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:skills/*.md");
            DocumentSplitter splitter = DocumentSplitters.recursive(500, 80);

            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                String content;
                try (InputStream inputStream = resource.getInputStream()) {
                    content = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
                }

                Metadata metadata = new Metadata();
                metadata.put("type", "static_customer_knowledge");
                metadata.put("source", fileName);
                metadata.put("category", categoryOf(fileName));

                List<TextSegment> segments = splitter.split(Document.from(content, metadata));
                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
                staticKnowledgeEmbeddingStore.addAll(embeddings, segments);

                documentCount++;
                segmentCount += segments.size();
                log.debug("已加载静态知识文档: {}，分片数: {}", fileName, segments.size());
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载静态客服知识文档失败", e);
        }

        log.info("静态客服知识加载完成，共 {} 份文档、{} 个片段", documentCount, segmentCount);
    }

    private String categoryOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unknown";
        }
        int extensionIndex = fileName.lastIndexOf('.');
        return extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
    }
}
