package com.hmdp.utils;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaticKnowledgeInitializerTest {

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;
    @Mock
    private EmbeddingModel embeddingModel;

    private StaticKnowledgeInitializer initializer;
    private final List<TextSegment> ingestedSegments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        initializer = new StaticKnowledgeInitializer(embeddingStore, embeddingModel);

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = segments.stream()
                    .map(segment -> Embedding.from(new float[]{1.0f, 0.0f}))
                    .collect(Collectors.toList());
            return Response.from(embeddings);
        });
        when(embeddingStore.addAll(anyList(), anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(1);
            ingestedSegments.addAll(segments);
            return Collections.nCopies(segments.size(), "id");
        });
    }

    @Test
    void shouldLoadOnlyStaticCustomerKnowledgeDocuments() throws Exception {
        initializer.run(null);

        assertFalse(ingestedSegments.isEmpty());
        assertTrue(ingestedSegments.stream().allMatch(segment ->
                "static_customer_knowledge".equals(segment.metadata().getString("type"))));
        assertTrue(ingestedSegments.stream().allMatch(segment ->
                segment.metadata().getString("source").endsWith(".md")));
        assertTrue(ingestedSegments.stream().noneMatch(segment ->
                "shop".equals(segment.metadata().getString("type"))
                        || "blog".equals(segment.metadata().getString("type"))));
        verify(embeddingModel, atLeastOnce()).embedAll(anyList());
    }
}
