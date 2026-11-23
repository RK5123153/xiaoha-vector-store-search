package com.quanxiaoha.vector.store.service.impl;

import com.quanxiaoha.vector.store.config.SearXNGProperties;
import com.quanxiaoha.vector.store.exception.SearXNGException;
import com.quanxiaoha.vector.store.model.SearchResult;
import com.quanxiaoha.vector.store.service.SearXNGService;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * SearXNG 联网搜索实现。
 * <p>
 * 调用链路：OkHttp 发 GET 请求 → {@code format=json} 让 SearXNG 返回 JSON →
 * 解析 {@code results} 数组 → 按 score 倒序并截断。
 * <p>
 * SearXNG 自身已经按 URL 做过一次去重（同一个页面被多个引擎命中时只保留一条，
 * 并把命中它的引擎合并到 {@code engines} 字段），这里不再重复去重。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
@Service
public class SearXNGServiceImpl implements SearXNGService {

    private static final Logger log = LoggerFactory.getLogger(SearXNGServiceImpl.class);

    /**
     * 引擎正常时的状态描述。
     */
    private static final String ENGINE_OK = "正常";

    private final OkHttpClient okHttpClient;
    private final JsonMapper jsonMapper;
    private final SearXNGProperties properties;

    public SearXNGServiceImpl(OkHttpClient okHttpClient, JsonMapper jsonMapper, SearXNGProperties properties) {
        this.okHttpClient = okHttpClient;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    @Override
    public List<SearchResult> search(String query) {
        return search(query, null);
    }

    @Override
    public List<SearchResult> search(String query, Integer limit) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        int maxCount = resolveCount(limit);

        SearchResponse response = doSearch(query);
        List<SearchResult> results = response.results().stream()
                .sorted(Comparator.comparingDouble((SearchResult r) -> r.score() == null ? 0.0 : r.score()).reversed())
                .limit(maxCount)
                .toList();

        // 一条结果都没有，但有引擎报错 —— 这不是「没搜到」，是搜索引擎挂了，得让调用方知道
        if (results.isEmpty() && hasFailedEngine(response.engineStatus())) {
            throw new SearXNGException("没有搜到结果，且以下搜索引擎不可用：" + failedEngines(response.engineStatus()));
        }

        log.info("## SearXNG 搜索[{}]命中 {} 条结果，引擎状态：{}", query, results.size(), response.engineStatus());
        return results;
    }

    @Override
    public Map<String, String> probeEngines(String query) {
        SearchResponse response = doSearch(StringUtils.hasText(query) ? query : "test");
        log.info("## SearXNG 引擎探测结果：{}", response.engineStatus());
        return response.engineStatus();
    }

    /**
     * 实际返回条数：接口传入的条数不能超过配置的上限，避免一次拉回几百条把上下文撑爆。
     */
    private int resolveCount(Integer limit) {
        int configured = properties.count() > 0 ? properties.count() : 10;
        if (limit == null || limit <= 0) {
            return configured;
        }
        return Math.min(limit, configured);
    }

    /**
     * 执行一次搜索，返回结果和各引擎的可用状态。
     */
    private SearchResponse doSearch(String query) {
        Request request = new Request.Builder()
                .url(buildUrl(query))
                .get()
                .header("Accept", "application/json")
                .build();

        String body = execute(request);
        JsonNode root = parse(body);
        List<SearchResult> results = toSearchResults(root.path("results"));
        return new SearchResponse(results, readEngineStatus(root, results));
    }

    /**
     * 拼接 SearXNG 搜索地址。
     * <p>
     * 用 HttpUrl 的 Builder 而不是自己拼字符串：关键词里的中文、空格、& 都会被自动编码。
     */
    private HttpUrl buildUrl(String query) {
        if (!StringUtils.hasText(properties.url())) {
            throw new SearXNGException("SearXNG 地址未配置（app.searxng.url）");
        }
        HttpUrl baseUrl;
        try {
            baseUrl = HttpUrl.get(properties.url());
        } catch (IllegalArgumentException e) {
            throw new SearXNGException("SearXNG 地址配置不正确：" + properties.url(), e);
        }

        HttpUrl.Builder builder = baseUrl.newBuilder()
                .addQueryParameter("q", query) // 搜索关键词
                .addQueryParameter("format", "json"); // 指定返回 JSON 格式
        if (StringUtils.hasText(properties.engines())) {
            // 只聚合指定引擎；SearXNG 里没启用（或没装）的引擎会被自动忽略。
            // engines 留空时不传这个参数 —— 相当于用 SearXNG 的默认引擎集，
            // 这样在 SearXNG 界面里勾选新引擎后，代码这边不用改。
            builder.addQueryParameter("engines", properties.engines());
        }
        if (StringUtils.hasText(properties.language())) {
            builder.addQueryParameter("language", properties.language());
        }
        builder.addQueryParameter("safesearch", String.valueOf(properties.safesearch()));
        return builder.build();
    }

    /**
     * 发起请求并拿到响应体。
     */
    private String execute(Request request) {
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new SearXNGException("SearXNG 搜索失败，HTTP 状态码：" + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new SearXNGException("SearXNG 返回内容为空");
            }
            return responseBody.string();
        } catch (IOException e) {
            throw new SearXNGException("调用 SearXNG 接口失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解析 SearXNG 返回的 JSON。
     */
    private JsonNode parse(String body) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (JacksonException e) {
            throw new SearXNGException("解析 SearXNG 返回结果失败：" + e.getOriginalMessage(), e);
        }
        if (!root.path("results").isArray()) {
            // 没拿到 results 数组，多半是 SearXNG 没开启 JSON 格式
            // （settings.yml 中 search.formats 需要包含 json）
            throw new SearXNGException("SearXNG 返回结果中没有 results 数组，请确认已开启 JSON 格式"
                    + "（settings.yml 中 search.formats 加入 json）");
        }
        return root;
    }

    /**
     * 把 {@code results} 数组转成结果对象。
     */
    private List<SearchResult> toSearchResults(JsonNode results) {
        List<SearchResult> list = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            String url = node.path("url").asString("");
            if (!StringUtils.hasText(url)) {
                continue; // 没有链接的结果用不上，直接丢掉
            }
            list.add(new SearchResult(
                    url, // 页面访问链接
                    node.path("title").asString(""), // 网页标题
                    node.path("content").asString(""), // 搜索结果摘要
                    node.path("score").asDouble(0.0), // 相关性评分
                    node.path("engine").asString(""), // 命中的搜索引擎
                    node.path("publishedDate").asString(""), // 发布日期，没有则为空
                    null)); // 网页正文由后续小节抓取网页后填充
        }
        return list;
    }

    /**
     * 读取各搜索引擎的可用状态。
     * <p>
     * SearXNG 会把失败的引擎放在 {@code unresponsive_engines} 里，形如
     * {@code [["360search", "timeout"], ["bing", "HTTP connection error"]]}。
     * 这个字段很关键：结果为空可能是「真的没搜到」，也可能是「搜索引擎全挂了」，
     * 不区分开的话，调用方只能看到一个空列表，无从判断问题出在哪。
     *
     * @param root    完整响应
     * @param results 搜索结果
     * @return 引擎名 -> 状态（正常 / 失败原因）
     */
    private Map<String, String> readEngineStatus(JsonNode root, List<SearchResult> results) {
        Map<String, String> status = new LinkedHashMap<>();
        // 出了结果的引擎，先记为正常
        results.stream()
                .map(SearchResult::engine)
                .filter(StringUtils::hasText)
                .distinct()
                .forEach(engine -> status.put(engine, ENGINE_OK));

        JsonNode unresponsive = root.path("unresponsive_engines");
        if (unresponsive.isArray()) {
            for (JsonNode node : unresponsive) {
                String engine = node.path(0).asString("");
                String reason = node.path(1).asString("");
                if (!StringUtils.hasText(engine)) {
                    continue;
                }
                // 同一个引擎既出了结果又被记为失败时，以「能用」为准：
                // 部分请求失败不代表这个引擎整体不可用
                if (ENGINE_OK.equals(status.get(engine))) {
                    continue;
                }
                status.put(engine, reason.isBlank() ? "不可用" : reason);
            }
        }
        return status;
    }

    private boolean hasFailedEngine(Map<String, String> engineStatus) {
        return engineStatus.values().stream().anyMatch(status -> !ENGINE_OK.equals(status));
    }

    private String failedEngines(Map<String, String> engineStatus) {
        return engineStatus.entrySet().stream()
                .filter(entry -> !ENGINE_OK.equals(entry.getValue()))
                .map(entry -> entry.getKey() + "(" + entry.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    /**
     * 一次搜索的完整结果：搜索结果 + 各搜索引擎的可用状态。
     *
     * @param results      搜索结果
     * @param engineStatus 引擎名 -> 状态（正常 / 失败原因）
     */
    private record SearchResponse(List<SearchResult> results, Map<String, String> engineStatus) {
    }

}
