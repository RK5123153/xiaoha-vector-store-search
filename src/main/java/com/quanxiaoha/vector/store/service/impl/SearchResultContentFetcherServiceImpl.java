package com.quanxiaoha.vector.store.service.impl;

import com.quanxiaoha.vector.store.config.NetworkSearchProperties;
import com.quanxiaoha.vector.store.model.SearchResult;
import com.quanxiaoha.vector.store.service.SearchResultContentFetcherService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 搜索结果页面内容提取实现。
 * <p>
 * 分两个阶段，各用各的线程池：
 * <ol>
 *   <li><b>IO 阶段</b>（{@code httpRequestExecutor}）：并发发请求，把 HTML 拿回来；</li>
 *   <li><b>CPU 阶段</b>（{@code resultProcessingExecutor}）：用 Jsoup 解析 HTML、提取正文纯文本。</li>
 * </ol>
 * 为什么要分两段：HTML 解析是纯 CPU 计算，如果放在发请求的线程里做，
 * 那批线程就白占着（它们的价值是「同时等很多个网络响应」）。
 * 而把 HTML 直接塞进提示词是纯烧 token —— 一个页面动辄几十 KB，
 * 大半还是 css / js / 导航栏这些对问答没用的东西。
 * <p>
 * 与教程的两点差异，都是为了少踩坑：
 * <ul>
 *   <li><b>不把整页 HTML 存下来</b>：教程把 HTML 放进 {@code content} 字段留给后续处理，
 *       这里用一个内部中间类型 {@link FetchedPage} 装着，解析完就丢，不进 {@link SearchResult}。</li>
 *   <li><b>失败不等于空字符串</b>：教程失败时返回 {@code ""}，这里正文保持 {@code null}，
 *       两者在 JSON 里长得一样，但语义上「抓失败」和「页面本来就没正文」应该分得开。</li>
 * </ul>
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
@Service
public class SearchResultContentFetcherServiceImpl implements SearchResultContentFetcherService {

    private static final Logger log = LoggerFactory.getLogger(SearchResultContentFetcherServiceImpl.class);

    /**
     * 模拟真实浏览器访问，部分站点会拒绝 okhttp 默认的 User-Agent。
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * 抓网页时无用的标签：脚本、样式、导航栏、页眉页脚等，对问答没有帮助，反而稀释正文。
     * <p>
     * {@code textarea} 也要去掉：有的站点（如百度）会把整段 CSS 塞进 textarea 里，
     * 而 textarea 的内容是纯文本，Jsoup 不会把它当标签处理，结果一大段样式表代码混进正文。
     */
    private static final String NOISE_SELECTOR = "script, style, noscript, iframe, nav, header, footer, aside, "
            + "form, svg, template, textarea, input, button, select, option, label, object, embed, video, audio";

    /**
     * 抓取失败后的重试间隔（毫秒）。
     */
    private static final long RETRY_DELAY_MILLIS = 500;

    /**
     * IO 阶段的中间产物：拿到的原始 HTML。
     * <p>
     * 单独用一个内部类型装 HTML，而不是直接塞进 {@link SearchResult}：
     * {@code SearchResult} 是要返回给前端、也是要塞进提示词的对象，
     * 挂着几十 KB 的 HTML 既占内存，序列化出去更是灾难。
     *
     * @param url           页面地址
     * @param title         网页标题
     * @param content       搜索结果摘要
     * @param score         相关性评分
     * @param engine        命中的搜索引擎
     * @param publishedDate 发布日期
     * @param html          页面 HTML，解析失败时为 null
     */
    private record FetchedPage(String url, String title, String content, Double score, String engine,
                               String publishedDate, String html) {
    }

    private final OkHttpClient crawlClient;
    private final ThreadPoolTaskExecutor httpExecutor;
    private final ThreadPoolTaskExecutor processingExecutor;
    private final NetworkSearchProperties properties;

    /**
     * 单个页面的重试次数。
     */
    private final int retries;

    public SearchResultContentFetcherServiceImpl(OkHttpClient okHttpClient,
                                                 @Qualifier("httpRequestExecutor") ThreadPoolTaskExecutor httpExecutor,
                                                 @Qualifier("resultProcessingExecutor") ThreadPoolTaskExecutor processingExecutor,
                                                 NetworkSearchProperties properties) {
        // newBuilder() 复用同一个连接池和调度器，只换一套更短的超时，不用再造一个 OkHttpClient
        // 注意：OkHttp 里超时传 0 表示「不超时」，配置写错反而会让请求永远挂着，所以这里兜个底
        int callTimeout = Math.max(1, properties.fetchTimeout());
        this.crawlClient = okHttpClient.newBuilder()
                .connectTimeout(Math.min(5, callTimeout), TimeUnit.SECONDS)
                .readTimeout(callTimeout, TimeUnit.SECONDS)
                .callTimeout(callTimeout, TimeUnit.SECONDS) // 单次调用（含连接）的总时限
                .build();
        this.httpExecutor = httpExecutor;
        this.processingExecutor = processingExecutor;
        this.properties = properties;
        this.retries = Math.max(0, properties.retries());
    }

    /**
     * 并发批量获取搜索结果页面的内容。
     * <p>
     * 分两个阶段，各用各的线程池：
     * <ol>
     *   <li><b>IO 阶段</b>（{@code httpRequestExecutor}）：发请求、拿 HTML。这段是等待网络，用不着 CPU；</li>
     *   <li><b>CPU 阶段</b>（{@code resultProcessingExecutor}）：用 Jsoup 解析 HTML、提取正文。
     *       解析是纯 CPU 计算，放在 IO 线程里做等于让那些线程干等着不干活。</li>
     * </ol>
     *
     * @param searchResults 待抓取的搜索结果
     * @param timeout       单个页面的超时时间
     * @param unit          超时时间单位
     * @return 抓取完成后的结果列表（顺序与入参一致）
     */
    @Override
    public CompletableFuture<List<SearchResult>> batchFetch(List<SearchResult> searchResults, long timeout, TimeUnit unit) {
        if (searchResults == null || searchResults.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        // 步骤 1：为每个搜索结果创建独立的异步获取任务（IO 阶段，只负责把 HTML 拿回来）
        List<CompletableFuture<FetchedPage>> futures = searchResults.stream()
                .map(result -> asyncFetchHtml(result, timeout, unit))
                .toList();

        // 步骤 2：合并所有独立任务为一个聚合任务
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

        // 步骤 3：所有任务完成后收集结果，并在 CPU 线程池里做 Jsoup 解析、提取正文
        return allFutures.thenApplyAsync(v -> futures.stream()
                        .map(SearchResultContentFetcherServiceImpl::join)
                        .filter(Objects::nonNull)
                        .map(this::toSearchResult)
                        .toList(),
                processingExecutor);
    }

    /**
     * 异步获取单个搜索结果对应的页面 HTML（IO 阶段）。
     * <p>
     * 超时和异常都兜住：拿不到就返回没有 HTML 的结果，
     * 一个页面出问题不会把整批结果带崩。
     *
     * @param result  搜索结果
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return 带页面 HTML 的中间产物
     */
    private CompletableFuture<FetchedPage> asyncFetchHtml(SearchResult result, long timeout, TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> {
                    String html = syncFetchHtmlContent(result.url());
                    return new FetchedPage(result.url(), result.title(), result.content(), result.score(),
                            result.engine(), result.publishedDate(), html);
                }, httpExecutor) // 使用专用的 httpExecutor 线程池
                // 超时处理：注意这只是「放弃等待」，底层那个 HTTP 调用还会跑到自己超时为止
                .completeOnTimeout(new FetchedPage(result.url(), result.title(), result.content(), result.score(),
                        result.engine(), result.publishedDate(), null), timeout, unit)
                // 异常处理
                .exceptionally(e -> {
                    log.warn("## 获取页面内容异常, URL: {}", result.url(), e);
                    return new FetchedPage(result.url(), result.title(), result.content(), result.score(),
                            result.engine(), result.publishedDate(), null);
                });
    }

    /**
     * 把 IO 阶段的产物转成最终结果：解析 HTML、提取正文（CPU 阶段）。
     */
    private SearchResult toSearchResult(FetchedPage page) {
        if (page.html() == null || page.html().isBlank()) {
            return new SearchResult(page.url(), page.title(), page.content(), page.score(), page.engine(),
                    page.publishedDate(), null);
        }
        String text = extractText(page.html());
        if (text.isBlank()) {
            log.info("## 页面没有正文，跳过 {}", page.url());
            return new SearchResult(page.url(), page.title(), page.content(), page.score(), page.engine(),
                    page.publishedDate(), null);
        }
        log.debug("## 提取正文完成：{}（HTML {} 字符 -> 正文 {} 字符，线程 {}）",
                page.url(), page.html().length(), text.length(), Thread.currentThread().getName());
        return new SearchResult(page.url(), page.title(), page.content(), page.score(), page.engine(),
                page.publishedDate(), text);
    }

    /**
     * 取已完成任务的结果；单个任务异常时返回 null，由上层过滤掉。
     */
    private static FetchedPage join(CompletableFuture<FetchedPage> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            return null;
        }
    }

    /**
     * 同步获取指定 URL 的 HTML 内容。
     * <p>
     * 用的是 OkHttp 的同步接口 —— 在线程池的线程里同步发请求是最省事的写法，
     * 不用再管回调和线程切换。
     *
     * @param url 页面地址
     * @return HTML 内容；失败时返回 null
     */
    private String syncFetchHtmlContent(String url) {
        Request request;
        try {
            request = new Request.Builder()
                    .url(url) // 设置要访问的目标 URL
                    .header("User-Agent", USER_AGENT) // 模拟真实浏览器访问
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("## 跳过非法链接 {}: {}", url, e.getMessage());
            return null;
        }

        for (int attempt = 0; attempt <= retries; attempt++) {
            try (Response response = crawlClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("## 抓取失败 {}，HTTP {}", url, response.code());
                    return null;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    return null;
                }
                String contentType = response.header("Content-Type", "");
                if (!isTextual(contentType)) {
                    log.info("## 跳过非文本页面 {}（{}）", url, contentType);
                    return null;
                }
                // body.string() 会按响应头里的 charset 解码，中文站点常见的 GB2312/GBK 也能正确还原
                return body.string();
            } catch (IOException e) {
                // 连接超时、连接被重置这类问题很常见（尤其是同一个站点的并发建连），
                // 重试一次通常就好了，所以失败先别急着放弃
                if (attempt < retries) {
                    log.info("## 抓取 {} 第 {} 次失败（{}），稍后重试", url, attempt + 1, e.getMessage());
                    sleep(RETRY_DELAY_MILLIS);
                    continue;
                }
                log.warn("## 抓取异常 {}: {}", url, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * 只处理 HTML / 纯文本，PDF、图片这类交给大模型也读不懂。
     */
    private boolean isTextual(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true; // 有些站点不返回 Content-Type，先当文本处理，解析不出正文自然会跳过
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("text/html") || lower.contains("application/xhtml")
                || lower.contains("text/plain") || lower.contains("application/xml") || lower.contains("text/xml");
    }

    /**
     * 从 HTML 里抠出正文纯文本。
     */
    private String extractText(String html) {
        Document document = Jsoup.parse(html);
        document.select(NOISE_SELECTOR).remove();
        String text = document.body() != null ? document.body().text() : document.text();
        text = text.replaceAll("\\s+", " ").trim();
        // 有些站点的标签不闭合，正文里会残留 <style ...> 这类字面量标记，清掉再交给模型
        text = text.replaceAll("<[^>]{1,300}>", " ").replaceAll("\\s+", " ").trim();
        int limit = Math.max(1, properties.contentLength());
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
