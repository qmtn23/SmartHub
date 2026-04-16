package com.hmdp.utils;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.document.DocumentSplitter;
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
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class SkillKnowledgeInitializer implements ApplicationRunner {

    private final EmbeddingStore<TextSegment> skillEmbeddingStore;
    private final EmbeddingModel embeddingModel;

    public SkillKnowledgeInitializer(
            @Qualifier("skillEmbeddingStore") EmbeddingStore<TextSegment> skillEmbeddingStore,
            EmbeddingModel embeddingModel) {
        this.skillEmbeddingStore = skillEmbeddingStore;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始加载 Skill SOP 文档...");
        int count = 0;

        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:skills/*.md");

            DocumentSplitter splitter = DocumentSplitters.recursive(500, 80);

            for (Resource resource : resources) {
                String content = StreamUtils.copyToString(
                        resource.getInputStream(), StandardCharsets.UTF_8);
                String fileName = resource.getFilename();

                Metadata metadata = new Metadata();
                metadata.put("type", "skill");
                metadata.put("source", fileName);

                Document doc = Document.from(content, metadata);
                List<TextSegment> segments = splitter.split(doc);
                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
                skillEmbeddingStore.addAll(embeddings, segments);
                count += segments.size();

                log.debug("已加载 Skill 文档: {}，分片数: {}", fileName, segments.size());
            }
        } catch (IOException e) {
            log.error("加载 Skill 文档失败", e);
        }

        log.info("Skill SOP 文档加载完成，共 {} 个片段", count);
    }
}
