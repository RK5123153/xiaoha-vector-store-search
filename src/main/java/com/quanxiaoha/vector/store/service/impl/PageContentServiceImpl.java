package com.quanxiaoha.vector.store.service.impl;

import com.quanxiaoha.vector.store.config.NetworkSearchProperties;
import com.quanxiaoha.vector.store.model.SearchResult;
import com.quanxiaoha.vector.store.service.PageContentService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 网页正文抓取实现。
 * <p>
 * 流程：并发发起 HTTP 请求 → 拿到 HTML → 用 Jsoup 抠出正文 → 截断后回填。
 * <p>
 * 两个容易踩的坑：
 * <ul>
 *   <li><b>并发不是越多越好</b>：50 个网页一起打过去，很容易被目标站点当成攻击而被拒，
 *       所以用固定线程池限流（{@code app.network.concurrency}）。</li>
 *   <li><b>整页 HTML 不能直接喂给模型</b>：一个页面的 HTML 动辄几万字符，
 *       里面大半是样式表和脚本，纯粹浪费 token；抠出正文再截断才有性价比。</li>
 * </ul>
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
@Service
public class PageContentServiceImpl implements PageContentService {

    private static final Logger log = LoggerFactory.getLogger(PageContentServiceImpl.class);

    /**
     * 部分站点会拒绝 okhttp 默认的 User-Agent，直接给个浏览器 UA 更稳妥。
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * 抓网页时无用的标签：脚本、样式、导航栏、页眉页脚等，
     * 这些内容对问答没有帮助，反而会稀释正文。
     * <p>
     * {@code textarea} 也要去掉：有的站点（如百度）会把整段 CSS 塞进 textarea 里，
     * 而 textarea 的内容是纯文本，Jsoup 不会把它当标签处理，
     * 结果一大段样式表代码就混进正文里了。
     */
    private static final String NOISE_SELECTOR = "script, style, noscript, iframe, nav, header, footer, aside, "
            + "form, svg, template, textarea, input, button, select, option, label, object, embed, video, audio";

    /**
     * 页面被截断后的重试次数（不含首次请求）。
     * <p>
     * 网络抖动、目标站点临时限流都可能造成单次请求失败，
     * 抓网页这种「尽力而为」的场景下重试一次比直接放弃划算得多。
     */
    private static final int MAX_RETRY = 1;

    private final OkHttpClient crawlClient;
    private final ExecutorService executor;
    private final NetworkSearchProperties properties;

    public PageContentServiceImpl(OkHttpClient okHttpClient, ExecutorService networkSearchExecutor,
                                  NetworkSearchProperties properties) {
        // newBuilder() 会复用同一个连接池和调度器，只是换一套更短的超时，
        // 不用为抓网页再造一个 OkHttpClient。
        this.crawlClient = okHttpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(properties.fetchTimeout(), TimeUnit.SECONDS)
                .callTimeout(properties.fetchTimeout(), TimeUnit.SECONDS) // 整个调用（含连接）的总时限
                .build();
        this.executor = networkSearchExecutor;
        this.properties = properties;
    }

    @Override
    public List<SearchResult> fillPageContent(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        List<SearchResult> targets = results.stream()
                .limit(Math.max(1, properties.maxPages())) // 按相关性评分从高到低，只抓前 N 个
                .toList();

        List<CompletableFuture<SearchResult>> futures = new ArrayList<>(targets.size());
        for (SearchResult target : targets) {
            futures.add(CompletableFuture.supplyAsync(() -> fetch(target), executor));
        }

        // 给整批抓取一个总时限：个别站点卡住时，不至于把整个请求拖死
        long totalTimeout = properties.fetchTimeout() + 10L;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(totalTimeout, TimeUnit.SECONDS)
                    .exceptionally(e -> null) // 超时/异常都不往外抛，下面按单个 future 收集
                    .join();
        } catch (CompletionException e) {
            log.warn("## 批量抓取网页超时，已跳过未完成的页面: {}", e.getMessage());
        }

        List<SearchResult> filled = new ArrayList<>(futures.size());
        for (CompletableFuture<SearchResult> future : futures) {
            SearchResult result = join(future);
            if (result != null) {
                filled.add(result);
            }
        }
        long success = filled.stream().filter(r -> r.pageContent() != null).count();
        log.info("## 网页正文抓取完成：成功 {} / 共 {}", success, targets.size());
        return filled;
    }

    /**
     * 取已完成的任务结果，未完成或失败的一律跳过。
     */
    private SearchResult join(CompletableFuture<SearchResult> future) {
        try {
            return future.getNow(null);
        } catch (CompletionException e) {
            return null;
        }
    }

    /**
     * 抓取单个页面，失败时返回原对象（正文为 null），不影响整体流程。
     */
    private SearchResult fetch(SearchResult result) {
        String url = result.url();
        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("## 跳过非法链接 {}: {}", url, e.getMessage());
            return result;
        }

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try (Response response = crawlClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("## 抓取失败 {}，HTTP {}", url, response.code());
                    return result;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    return result;
                }
                String contentType = response.header("Content-Type", "");
                if (!isTextual(contentType)) {
                    log.info("## 跳过非文本页面 {}（{}）", url, contentType);
                    return result;
                }
                // body.string() 会按响应头里的 charset 解码，中文站点常见的 GB2312/GBK 也能正确还原
                String html = body.string();
                String text = extractText(html);
                if (text.isBlank()) {
                    log.info("## 页面没有正文，跳过 {}", url);
                    return result;
                }
                String title = result.title() == null || result.title().isBlank() ? extractTitle(html) : result.title();
                return new SearchResult(url, title, result.content(), result.score(), result.engine(),
                        result.publishedDate(), text);
            } catch (IOException e) {
                if (attempt < MAX_RETRY) {
                    log.info("## 抓取 {} 第 {} 次失败（{}），稍后重试", url, attempt + 1, e.getMessage());
                    sleep(500L);
                    continue;
                }
                log.warn("## 抓取异常 {}: {}", url, e.getMessage());
                return result;
            }
        }
        return result;
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

    private String extractTitle(String html) {
        String title = Jsoup.parse(html).title();
        return title == null ? "" : title.trim();
    }

}
