package com.quanxiaoha.vector.store.controller;

import com.quanxiaoha.vector.store.exception.SearXNGException;
import com.quanxiaoha.vector.store.model.SearchResult;
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

import java.util.List;
import java.util.Map;

/**
 * 联网搜索：调用 SearXNG 聚合引擎，把各大搜索引擎去重后的结果取回来。
 * <p>
 * 这一步只拿到「链接 + 摘要 + 评分」，
 * 后续小节再访问这些链接抓取网页正文，拼成增强提示词交给大模型。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
@RestController
@RequestMapping("/network")
@Tag(name = "4. 联网搜索", description = "调用 SearXNG 聚合引擎，获取各大搜索引擎去重后的结果")
public class NetworkSearchController {

    private final SearXNGService searXNGService;

    public NetworkSearchController(SearXNGService searXNGService) {
        this.searXNGService = searXNGService;
    }

    /**
     * 联网搜索。
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
     * @param message 搜索关键词
     * @param count   最多返回多少条，可为空
     */
    public record NetworkSearchRequest(String message, Integer count) {
    }

}
