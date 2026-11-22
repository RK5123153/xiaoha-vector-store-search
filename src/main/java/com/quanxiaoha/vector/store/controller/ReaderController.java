package com.quanxiaoha.vector.store.controller;

import com.quanxiaoha.vector.store.reader.MyHtmlReader;
import com.quanxiaoha.vector.store.reader.MyJsonReader;
import com.quanxiaoha.vector.store.reader.MyMarkdownReader;
import com.quanxiaoha.vector.store.reader.MyPdfReader;
import com.quanxiaoha.vector.store.reader.MyTikaPptReader;
import com.quanxiaoha.vector.store.reader.MyTikaWordReader;
import com.quanxiaoha.vector.store.reader.MyTextReader;
import com.quanxiaoha.vector.store.service.DocumentIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
    private final DocumentIngestService documentIngestService;

    public ReaderController(MyTextReader textReader, MyJsonReader jsonReader, MyMarkdownReader markdownReader,
                            MyHtmlReader htmlReader, MyPdfReader pdfReader, MyTikaWordReader tikaWordReader,
                            MyTikaPptReader tikaPptReader, DocumentIngestService documentIngestService) {
        this.textReader = textReader;
        this.jsonReader = jsonReader;
        this.markdownReader = markdownReader;
        this.htmlReader = htmlReader;
        this.pdfReader = pdfReader;
        this.tikaWordReader = tikaWordReader;
        this.tikaPptReader = tikaPptReader;
        this.documentIngestService = documentIngestService;
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
     * @param type txt / json / md / html / pdf / word / ppt / all，多个用逗号分隔
     * @return 各格式写入的条数
     */
    @PostMapping(value = "/ingest")
    @Operation(summary = "文档入库",
            description = "把指定格式的文档向量化后写入 Redis 向量库。重复调用是覆盖写，不会产生重复数据。"
                    + "type 支持 all（全部格式）或用逗号分隔的组合，如 pdf,word,ppt")
    public Map<String, Integer> ingest(
            @Parameter(description = "文档格式：txt/json/md/html/pdf/word/ppt/all，多个用逗号分隔", example = "all")
            @RequestParam(value = "type", defaultValue = "all") String type) {
        return documentIngestService.ingest(type);
    }

    /**
     * 参数不合法时返回 400，而不是默认的 500。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

}
