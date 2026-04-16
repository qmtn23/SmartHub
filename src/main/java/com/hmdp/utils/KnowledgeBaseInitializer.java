package com.hmdp.utils;

import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Component
public class KnowledgeBaseInitializer implements ApplicationRunner {

    @Resource
    private IShopService shopService;
    @Resource
    private IBlogService blogService;
    @Resource
    private EmbeddingStoreIngestor ingestor;

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始初始化知识库...");

        List<Shop> shops = shopService.list();
        for (Shop shop : shops) {
            String text = String.format(
                    "店铺：%s，地址：%s，商圈：%s，人均：%d元，评分：%.1f，销量：%d，评论数：%d，营业时间：%s",
                    shop.getName(), shop.getAddress(), shop.getArea(),
                    shop.getAvgPrice(), shop.getScore() / 10.0,
                    shop.getSold(), shop.getComments(), shop.getOpenHours());
            Metadata metadata = new Metadata();
            metadata.put("type", "shop");
            metadata.put("shopId", shop.getId());
            ingestor.ingest(Document.from(text, metadata));
        }

        List<Blog> blogs = blogService.query()
                .orderByDesc("liked")
                .last("LIMIT 100")
                .list();
        for (Blog blog : blogs) {
            String content = blog.getContent() != null ? blog.getContent() : "";
            String title = blog.getTitle() != null ? blog.getTitle() : "无标题";
            String text = String.format("探店笔记标题：%s，内容：%s", title, content);
            Metadata metadata = new Metadata();
            metadata.put("type", "blog");
            metadata.put("blogId", blog.getId());
            ingestor.ingest(Document.from(text, metadata));
        }

        log.info("知识库初始化完成，已加载 {} 条店铺、{} 条笔记", shops.size(), blogs.size());
    }
}
