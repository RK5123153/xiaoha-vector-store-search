package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTML 文件读取器。
 * <p>
 * {@link JsoupDocumentReader} 基于 CSS 选择器抽取正文，并把 {@code <meta>} 标签写进元数据。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Component
public class MyHtmlReader {

    private static final String FILENAME = "my-page.html";

    private final Resource resource;

    public MyHtmlReader(@Value("classpath:/document/" + FILENAME) Resource resource) {
        this.resource = resource;
    }

    /**
     * 读取 HTML 文件。
     */
    public List<Document> loadHtml() {
        // JsoupDocumentReader 阅读器配置类
        JsoupDocumentReaderConfig config = JsoupDocumentReaderConfig.builder()
                .selector("article p") // 提取 <article> 标签内的 p 段落
                .charset("UTF-8") // 使用 UTF-8 编码
                .includeLinkUrls(true) // 在元数据中包含链接 URL（绝对链接）
                .metadataTags(List.of("author", "date")) // 提取 author 和 date 元标签
                .additionalMetadata("source", FILENAME) // 添加自定义元数据
                .additionalMetadata("filename", FILENAME)
                .additionalMetadata("sourceType", "html")
                .build();

        // 新建 JsoupDocumentReader 阅读器
        JsoupDocumentReader reader = new JsoupDocumentReader(resource, config);

        // 读取并转换为 Document 文档集合
        List<Document> documents = reader.get();

        // 固定 id，重复入库是覆盖写而不是追加
        List<Document> result = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            result.add(StableId.of(documents.get(i), "html:" + FILENAME + ":" + i, Map.of()));
        }
        return result;
    }

}
