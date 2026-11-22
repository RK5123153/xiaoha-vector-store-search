package com.quanxiaoha.vector.store.controller;

import com.quanxiaoha.vector.store.reader.MyHtmlReader;
import com.quanxiaoha.vector.store.reader.MyJsonReader;
import com.quanxiaoha.vector.store.reader.MyMarkdownReader;
import com.quanxiaoha.vector.store.reader.MyPdfReader;
import com.quanxiaoha.vector.store.reader.MyTikaPptReader;
import com.quanxiaoha.vector.store.reader.MyTikaWordReader;
import com.quanxiaoha.vector.store.reader.MyTextReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多种格式文档的读取与入库。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@RestController
@RequestMapping("/read")
@Tag(name = "1. 文档读取", description = "把 txt / json / markdown / html / pdf / word / ppt 解析成 Document 文档集合")
public class ReaderController {

    private final MyTextReader textReader;
    private final MyJsonReader jsonReader;
    private final MyMarkdownReader markdownReader;
    private final MyHtmlReader htmlReader;
    private final MyPdfReader pdfReader;
    private final MyTikaWordReader tikaWordReader;
    private final MyTikaPptReader tikaPptReader;
    private final VectorStore vectorStore;

    public ReaderController(MyTextReader textReader, MyJsonReader jsonReader, MyMarkdownReader markdownReader,
                            MyHtmlReader htmlReader, MyPdfReader pdfReader, MyTikaWordReader tikaWordReader,
                            MyTikaPptReader tikaPptReader, VectorStore vectorStore) {
        this.textReader = textReader;
        this.jsonReader = jsonReader;
        this.markdownReader = markdownReader;
        this.htmlReader = htmlReader;
        this.pdfReader = pdfReader;
        this.tikaWordReader = tikaWordReader;
        this.tikaPptReader = tikaPptReader;
        this.vectorStore = vectorStore;
    }

    /**
     * 读取 Txt 文档（整个文件作为一条 Document）。
     */
    @GetMapping(value = "/txt")
    @Operation(summary = "读取 Txt 文档", description = "不做任何切分，整个文本作为一条 Document 返回")
    public List<Document> readText() {
        return textReader.loadText();
    }

    /**
     * 读取 Txt 文档并分块拆分。
     */
    @GetMapping(value = "/txt2")
    @Operation(summary = "读取 Txt 文档并分块", description = "用 TokenTextSplitter 切块，长文本入库前建议用这个")
    public List<Document> readText2() {
        return textReader.loadTextAndSplit();
    }

    /**
     * 读取 Json 文档。
     */
    @GetMapping(value = "/json")
    @Operation(summary = "读取 Json 文档", description = "Json 数组的每个元素转换成一条 Document")
    public List<Document> readJson() {
        return jsonReader.loadJson();
    }

    /**
     * 读取 Markdown 文档。
     */
    @GetMapping(value = "/md")
    @Operation(summary = "读取 Markdown 文档", description = "按标题层级切分，代码块 / 块引用可单独成篇")
    public List<Document> readMarkdown() {
        return markdownReader.loadMarkdown();
    }

    /**
     * 读取 Html 文档。
     */
    @GetMapping(value = "/html")
    @Operation(summary = "读取 Html 文档", description = "基于 CSS 选择器抽取正文，并把 meta 标签写进元数据")
    public List<Document> readHtml() {
        return htmlReader.loadHtml();
    }

    /**
     * 读取 Pdf 文档，每页一条。
     */
    @GetMapping(value = "/pdf")
    @Operation(summary = "读取 Pdf 文档", description = "每页转换成一条 Document，分页粒度由 app.reader.pdf.pages-per-document 控制")
    public List<Document> readPdf() {
        return pdfReader.getDocsFromPdf();
    }

    /**
     * 读取 Word 文档（Apache Tika 解析后分块）。
     */
    @GetMapping(value = "/word")
    @Operation(summary = "读取 Word 文档", description = "Apache Tika 解析 docx 后用 TokenTextSplitter 切块")
    public List<Document> readWord() {
        return tikaWordReader.load();
    }

    /**
     * 读取 PPT 文档（Apache Tika 解析后分块）。
     */
    @GetMapping(value = "/ppt")
    @Operation(summary = "读取 PPT 文档", description = "Apache Tika 解析 pptx 后用 TokenTextSplitter 切块")
    public List<Document> readPpt() {
        return tikaPptReader.load();
    }

    /**
     * 把指定格式的文档向量化后写入 Redis 向量库。
     *
     * @param type txt / json / md / html / pdf / word / ppt / all
     * @return 各格式写入的条数
     */
    @PostMapping(value = "/ingest")
    @Operation(summary = "文档入库", description = "把指定格式的文档向量化后写入 Redis 向量库，重复调用是覆盖写不会产生重复数据")
    public Map<String, Integer> ingest(
            @Parameter(description = "文档格式：txt/json/md/html/pdf/word/ppt/all", example = "all")
            @RequestParam(value = "type", defaultValue = "all") String type) {
        Map<String, List<Document>> loaded = new LinkedHashMap<>();
        if (matches(type, "txt")) {
            // 长文本入库前先分块，否则单个超大向量会稀释语义
            loaded.put("txt", textReader.loadTextAndSplit());
        }
        if (matches(type, "json")) {
            loaded.put("json", jsonReader.loadJson());
        }
        if (matches(type, "md", "markdown")) {
            // sourceType 取值与各 Reader 内部保持一致，避免过滤检索时对不上
            loaded.put("markdown", markdownReader.loadMarkdown());
        }
        if (matches(type, "html")) {
            loaded.put("html", htmlReader.loadHtml());
        }
        if (matches(type, "pdf")) {
            loaded.put("pdf", pdfReader.getDocsFromPdf());
        }
        if (matches(type, "word", "docx")) {
            loaded.put("word", tikaWordReader.load());
        }
        if (matches(type, "ppt", "pptx")) {
            loaded.put("ppt", tikaPptReader.load());
        }
        if (loaded.isEmpty()) {
            throw new IllegalArgumentException(
                    "不支持的文档类型: " + type + "，可选值: txt/json/md/html/pdf/word/ppt/all");
        }

        Map<String, Integer> result = new LinkedHashMap<>();
        loaded.forEach((key, documents) -> {
            // sourceType 由各 Reader 自己写入，这里不再覆盖
            vectorStore.add(documents);
            result.put(key, documents.size());
        });
        return result;
    }

    private boolean matches(String type, String... candidates) {
        if ("all".equalsIgnoreCase(type)) {
            return true;
        }
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

}
