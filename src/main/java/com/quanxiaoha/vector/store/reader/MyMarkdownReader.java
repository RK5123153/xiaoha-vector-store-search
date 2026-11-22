package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Markdown 文件读取器。
 * <p>
 * {@link MarkdownDocumentReader} 会按标题层级切分文档，代码块、块引用、水平线都可以选择是否单独成篇。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Component
public class MyMarkdownReader {

    private static final String FILENAME = "code.md";

    private final Resource resource;

    public MyMarkdownReader(@Value("classpath:/document/" + FILENAME) Resource resource) {
        this.resource = resource;
    }

    /**
     * 读取 Markdown 文件。
     */
    public List<Document> loadMarkdown() {
        // MarkdownDocumentReader 阅读器配置类
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true) // 遇到水平线 ---，则创建新文档
                .withIncludeCodeBlock(false) // 排除代码块（代码块生成单独文档）
                .withIncludeBlockquote(false) // 排除块引用（块引用生成单独文档）
                .withAdditionalMetadata("filename", FILENAME) // 添加自定义元数据，如文件名称
                .withAdditionalMetadata("sourceType", "markdown")
                .build();

        // 新建 MarkdownDocumentReader 阅读器
        MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);

        // 读取并转换为 Document 文档集合
        List<Document> documents = reader.get();

        // 固定 id，重复入库是覆盖写而不是追加
        List<Document> result = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            result.add(StableId.of(documents.get(i), "md:" + FILENAME + ":" + i, Map.of()));
        }
        return result;
    }

}
