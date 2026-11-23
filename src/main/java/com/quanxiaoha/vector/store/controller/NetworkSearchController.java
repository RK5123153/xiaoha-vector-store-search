package com.quanxiaoha.vector.store.controller;

import com.quanxiaoha.vector.store.exception.SearXNGException;
import com.quanxiaoha.vector.store.model.SearchResult;
import com.quanxiaoha.vector.store.service.NetworkSearchAssistantService;
import com.quanxiaoha.vector.store.service.SearXNGService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 联网搜索：调用 SearXNG 聚合引擎，把各大搜索引擎去重后的结果取回来，
 * 再并发抓取这些网页的正文，拼成增强提示词交给大模型作答。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
@RestController
@RequestMapping("/network")
@Tag(name = "4. 联网搜索", description = "调用 SearXNG 聚合引擎获取结果，并抓取网页正文增强大模型回答")
public class NetworkSearchController {

    private final SearXNGService searXNGService;
    private final NetworkSearchAssistantService assistantService;

    public NetworkSearchController(SearXNGService searXNGService, NetworkSearchAssistantService assistantService) {
        this.searXNGService = searXNGService;
        this.assistantService = assistantService;
    }

    /**
     * 联网搜索（只拿搜索结果，不抓网页）。
     *
     * @param message 搜索关键词
     * @param count   最多返回多少条，不传则用配置里的条数
     * @return 搜索结果列表，按相关性评分倒序
     */
    @GetMapping(value = "/search")
    @Operation(summary = "联网搜索", description = "调用 SearXNG 聚合引擎，返回网页链接、标题、摘要与相关性评分")
    public List<SearchResult> search(
            @Parameter(description = "搜索关键词", example = "犬小哈教程")
            @RequestParam(value = "message") String message,
            @Parameter(description = "最多返回多少条，不传则用配置值", example = "10")
            @RequestParam(value = "count", required = false) Integer count) {
        return searXNGService.search(message, count);
    }

    /**
     * 联网搜索（POST + JSON body）。
     * <p>
     * 中文关键词放 URL 里既难读又容易碰到长度限制，走 JSON body 更稳妥。
     *
     * @param request 请求体
     * @return 搜索结果列表，按相关性评分倒序
     */
    @PostMapping(value = "/search")
    @Operation(summary = "联网搜索（POST）", description = "同 GET 版本，但关键词走 JSON body，适合中文长关键词")
    public List<SearchResult> searchPost(@RequestBody NetworkSearchRequest request) {
        return searXNGService.search(request.message(), request.count());
    }

    /**
     * 搜索 + 并发抓取网页正文（调试用）。
     * <p>
     * 返回每个页面的 URL、相关性评分和抠出来的正文，方便确认「上下文到底抓到了什么」。
     *
     * @param message 搜索关键词
     * @return 带网页正文的搜索结果列表
     */
    @GetMapping(value = "/pages")
    @Operation(summary = "搜索并抓取网页正文", description = "在搜索结果基础上并发访问网页，提取正文文本，方便调试上下文内容")
    public List<SearchResult> pages(
            @Parameter(description = "搜索关键词", example = "犬小哈教程")
            @RequestParam(value = "message") String message) {
        return assistantService.searchPages(message);
    }

    /**
     * 查看拼装好的增强提示词（调试用）。
     * <p>
     * 走完「搜索 → 抓网页」之后，把模板渲染出来的完整提示词原样返回，
     * 方便确认上下文里到底装了什么、评分排序对不对。
     *
     * @param message 用户问题
     * @return 渲染后的完整提示词
     */
    @GetMapping(value = "/prompt")
    @Operation(summary = "查看增强提示词", description = "搜索并抓取网页后，返回模板渲染出来的完整提示词，方便调试上下文内容")
    public String prompt(
            @Parameter(description = "用户问题", example = "Spring AI 的向量数据库有哪些")
            @RequestParam(value = "message") String message) {
        List<SearchResult> pages = assistantService.searchPages(message);
        return assistantService.buildPrompt(message, pages);
    }

    /**
     * 联网搜索问答（非流式）。
     *
     * @param message 用户问题
     * @return 大模型基于网页内容给出的回答
     */
    @GetMapping(value = "/ask")
    @Operation(summary = "联网搜索问答", description = "搜索 → 抓网页正文 → 拼增强提示词 → 大模型作答，一次性返回完整回答")
    public String ask(
            @Parameter(description = "用户问题", example = "犬小哈教程")
            @RequestParam(value = "message") String message) {
        return assistantService.ask(message);
    }

    /**
     * 联网搜索问答（流式）。
     *
     * @param message 用户问题
     * @return 逐段返回的大模型回答
     */
    @GetMapping(value = "/askStream", produces = "text/html;charset=utf-8")
    @Operation(summary = "联网搜索问答（流式）", description = "同非流式版本，但回答逐段返回")
    public Flux<String> askStream(
            @Parameter(description = "用户问题", example = "犬小哈教程")
            @RequestParam(value = "message") String message) {
        return assistantService.askStream(message);
    }

    /**
     * 联网搜索问答（POST + JSON body）。
     *
     * @param request 请求体
     * @return 大模型基于网页内容给出的回答
     */
    @PostMapping(value = "/ask")
    @Operation(summary = "联网搜索问答（POST）", description = "同 GET 版本，但问题走 JSON body，适合中文长问题")
    public String askPost(@RequestBody NetworkSearchRequest request) {
        return assistantService.ask(request.message());
    }

    /**
     * 联网搜索问答（POST + JSON body，流式）。
     *
     * @param request 请求体
     * @return 逐段返回的大模型回答
     */
    @PostMapping(value = "/askStream", produces = "text/html;charset=utf-8")
    @Operation(summary = "联网搜索问答（POST，流式）", description = "同 GET 版本，但问题走 JSON body")
    public Flux<String> askStreamPost(@RequestBody NetworkSearchRequest request) {
        return assistantService.askStream(request.message());
    }

    /**
     * SearXNG 调不通时返回 502，而不是默认的 500，
     * 让调用方一眼看出「是外部搜索服务的问题，不是本服务的 bug」。
     */
    @ExceptionHandler(SearXNGException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> handleSearXNGException(SearXNGException e) {
        return Map.of("error", e.getMessage() == null ? "调用 SearXNG 失败" : e.getMessage());
    }

    /**
     * 参数不合法时返回 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        return Map.of("error", e.getMessage() == null ? "参数不合法" : e.getMessage());
    }

    /**
     * 联网搜索请求体。
     *
     * @param message 搜索关键词 / 用户问题
     * @param count   最多返回多少条，可为空
     */
    public record NetworkSearchRequest(String message, Integer count) {
    }

}
