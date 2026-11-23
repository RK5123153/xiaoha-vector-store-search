package com.quanxiaoha.vector.store.service.impl;

import com.quanxiaoha.vector.store.config.NetworkSearchProperties;
import com.quanxiaoha.vector.store.exception.SearXNGException;
import com.quanxiaoha.vector.store.model.SearchResult;
import com.quanxiaoha.vector.store.service.NetworkSearchAssistantService;
import com.quanxiaoha.vector.store.service.SearchResultContentFetcherService;
import com.quanxiaoha.vector.store.service.SearXNGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 联网搜索问答实现。
 * <p>
 * 完整链路：
 * <ol>
 *   <li>用用户问题去 SearXNG 搜索，拿到按相关性评分的链接；</li>
 *   <li>并发抓取前 N 个网页，提取正文；</li>
 *   <li>把正文按「来源 + 评分」拼成上下文，填进提示词模板；</li>
 *   <li>交给大模型作答。</li>
 * </ol>
 * 提示词模板放在 {@code resources/prompts/network-search.st}，
 * 用 Spring AI 的 {@link PromptTemplate} 渲染（默认 ST 渲染器，分隔符就是 {@code {}}）。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
@Service
public class NetworkSearchAssistantServiceImpl implements NetworkSearchAssistantService {

    private static final Logger log = LoggerFactory.getLogger(NetworkSearchAssistantServiceImpl.class);

    private static final String TEMPLATE_PATH = "prompts/network-search.st";

    /**
     * 一个页面都没抓到时的兜底提示。
     */
    private static final String NO_CONTENT_MESSAGE = "未能获取到任何网页正文，可能是目标站点访问受限，请稍后重试。";

    /**
     * 模型返回空内容时的兜底提示。
     */
    private static final String EMPTY_ANSWER_MESSAGE = "模型没有返回任何内容，请稍后重试。";

    /**
     * 判断两个页面内容是否重复时，取正文开头多少个字符做指纹。
     */
    private static final int FINGERPRINT_LENGTH = 200;

    private final SearXNGService searXNGService;
    private final SearchResultContentFetcherService contentFetcherService;
    private final ChatClient chatClient;
    private final PromptTemplate promptTemplate;
    private final NetworkSearchProperties properties;

    public NetworkSearchAssistantServiceImpl(SearXNGService searXNGService,
                                             SearchResultContentFetcherService contentFetcherService,
                                             ChatClient chatClient, NetworkSearchProperties properties) {
        this.searXNGService = searXNGService;
        this.contentFetcherService = contentFetcherService;
        this.chatClient = chatClient;
        this.properties = properties;
        // 模板在启动时就加载好：文件缺失要立刻暴露，而不是等用户提问才报错
        this.promptTemplate = new PromptTemplate(new ClassPathResource(TEMPLATE_PATH));
    }

    @Override
    public List<SearchResult> searchPages(String question) {
        // 第一步：搜索。SearXNG 返回的结果已按相关性评分排好序
        List<SearchResult> results = searXNGService.search(question);
        // 第二步：只抓相关性评分最高的前 N 个页面（页面越多，提示词越长、模型越慢）
        List<SearchResult> targets = results.stream()
                .limit(Math.max(1, properties.maxPages()))
                .toList();
        // 第三步：并发抓取网页正文
        long budget = properties.fetchBudgetSeconds();
        try {
            // 用 get 带超时，而不是无参 join()：万一某个任务卡住，不至于把请求线程一直占着
            return contentFetcherService.batchFetch(targets, budget, TimeUnit.SECONDS)
                    .get(budget + 5L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new SearXNGException("抓取网页正文超时（" + (budget + 5) + " 秒）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearXNGException("抓取网页正文被中断", e);
        } catch (ExecutionException e) {
            throw new SearXNGException("抓取网页正文失败：" + e.getCause().getMessage(), e.getCause());
        }
    }

    @Override
    public String buildPrompt(String question, List<SearchResult> pages) {
        String context = buildContext(pages);
        return promptTemplate.render(Map.<String, Object>of(
                "question", question,
                "context", context));
    }

    @Override
    public String ask(String question) {
        List<SearchResult> pages = searchPages(question);
        if (!hasContent(pages)) {
            log.warn("## 问题[{}]没有抓到任何网页正文，跳过大模型调用", question);
            return NO_CONTENT_MESSAGE;
        }
        String prompt = buildPrompt(question, pages);
        log.info("## 增强提示词长度：{} 字符", prompt.length());
        String answer = chatClient.prompt()
                .user(prompt) // 模板里已经包含「用户问题 + 上下文 + 任务要求」
                .call()
                .content();
        // 模型偶尔会返回空内容，这时给个说明，别让调用方拿到一个空 body
        return answer == null || answer.isBlank() ? EMPTY_ANSWER_MESSAGE : answer;
    }

    @Override
    public Flux<String> askStream(String question) {
        List<SearchResult> pages = searchPages(question);
        if (!hasContent(pages)) {
            log.warn("## 问题[{}]没有抓到任何网页正文，跳过大模型调用", question);
            return Flux.just(NO_CONTENT_MESSAGE);
        }
        String prompt = buildPrompt(question, pages);
        log.info("## 增强提示词长度：{} 字符", prompt.length());
        return chatClient.prompt()
                .user(prompt)
                .stream()
                .content();
    }

    /**
     * 把抓到的网页正文拼成上下文。
     * <p>
     * 每段前面标上「来源序号 + 相关性评分」，
     * 让模型知道哪些页面更可信 —— 评分高的内容优先采信。
     * <p>
     * 还会跳过内容重复的页面：搜索引擎经常会给出同一个页面的不同地址
     * （比如 {@code /page/1} 和 {@code /page/1/}），正文一模一样，
     * 重复塞进上下文既浪费配额，也会让模型误以为有两个来源在互相印证。
     */
    private String buildContext(List<SearchResult> pages) {
        StringBuilder context = new StringBuilder();
        Set<String> seen = new HashSet<>();
        int source = 1;
        int used = 0;
        int limit = Math.max(1, properties.maxContextLength());

        for (SearchResult page : pages) {
            String text = page.pageContent();
            if (text == null || text.isBlank()) {
                continue; // 没抓到正文的页面直接跳过
            }
            if (!seen.add(fingerprint(text))) {
                log.debug("## 跳过内容重复的页面：{}", page.url());
                continue;
            }
            int remaining = limit - used;
            if (remaining <= 0) {
                break; // 上下文已经到上限，剩下的页面不再拼进来
            }
            if (text.length() > remaining) {
                text = text.substring(0, remaining);
            }
            context.append("### 来源 ").append(source++)
                    .append(" | 相关性: ").append(formatScore(page.score()))
                    .append('\n')
                    .append("- URL: ").append(page.url()).append('\n')
                    .append("- 页面文本:\n").append(text).append("\n\n");
            used += text.length();
        }
        return context.toString();
    }

    /**
     * 用正文开头的一段做指纹，判断两个页面的内容是不是同一份。
     * <p>
     * 只取开头几百个字符：同一个页面的不同地址，正文通常完全一致；
     * 而不同页面的开头几乎不会撞车（标题和首段都不一样）。
     */
    private String fingerprint(String text) {
        return text.substring(0, Math.min(FINGERPRINT_LENGTH, text.length())).trim();
    }

    private boolean hasContent(List<SearchResult> pages) {
        return pages != null && pages.stream().anyMatch(p -> p.pageContent() != null && !p.pageContent().isBlank());
    }

    /**
     * 评分保留三位小数即可，太长的浮点数（如 0.3333333333333333）对模型没有意义。
     */
    private String formatScore(Double score) {
        return score == null ? "0.000" : String.format(Locale.ROOT, "%.3f", score);
    }

}
