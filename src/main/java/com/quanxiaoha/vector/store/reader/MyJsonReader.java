package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.JsonReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Json 文件读取器。
 * <p>
 * {@link JsonReader} 会把 Json 数组中的每个元素转换成一条 Document，
 * 指定的 key 会被拼接成 Document 的正文，其余字段进入元数据。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Component
public class MyJsonReader {

    private static final String FILENAME = "tv.json";

    /**
     * 来源标题，去掉扩展名。会拼进正文参与向量计算。
     */
    private static final String TITLE = "电视剧简介";

    private final Resource resource;

    public MyJsonReader(@Value("classpath:/document/" + FILENAME) Resource resource) {
        this.resource = resource;
    }

    /**
     * 读取 Json 文件。
     */
    public List<Document> loadJson() {
        // 创建 JsonReader 阅读器实例，配置需要读取的字段
        JsonReader jsonReader = new JsonReader(resource, "description", "content", "title");
        List<Document> documents = jsonReader.get();

        // 固定 id，重复入库是覆盖写而不是追加
        List<Document> result = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            result.add(Documents.withTitle(
                    Documents.withId(documents.get(i), "json:" + FILENAME + ":" + i, Map.of(
                            "sourceType", "json",
                            "filename", FILENAME)),
                    TITLE));
        }
        return result;
    }

}
