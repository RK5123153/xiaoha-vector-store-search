package com.quanxiaoha.vector.store.service.impl;

import com.quanxiaoha.vector.store.config.NetworkSearchProperties;
import com.quanxiaoha.vector.store.model.SearchResult;
import com.quanxiaoha.vector.store.service.NetworkSearchAssistantService;
import com.quanxiaoha.vector.store.service.PageContentService;
import com.quanxiaoha.vector.store.service.SearXNGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private final SearXNGService searXNGService;
    private final PageContentService pageContentService;
    private final ChatClient chatClient;
    private final PromptTemplate promptTemplate;
    private final NetworkSearchProperties properties;

    public NetworkSearchAssistantServiceImpl(SearXNGService searXNGService, PageContentService pageContentService,
                                             ChatClient chatClient, NetworkSearchProperties properties) {
        this.searXNGService = searXNGService;
        this.pageContentService = pageContentService;
        this.chatClient = chatClient;
        this.properties = properties;
        // 模板在启动时就加载好：文件缺失要立刻暴露，而不是等用户提问才报错
        this.promptTemplate = new PromptTemplate(new ClassPathResource(TEMPLATE_PATH));
    }

    @Override
    public List<SearchResult> searchPages(String question) {
        // 第一步：搜索。SearXNG 返回的结果已按相关性评分排好序
        List<SearchResult> results = searXNGService.search(question);
        // 第二步：并发抓取前 N 个网页的正文
        return pageContentService.fillPageContent(results);
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
        return chatClient.prompt()
                .user(prompt) // 模板里已经包含「用户问题 + 上下文 + 任务要求」
                .call()
                .content();
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
     */
    private String buildContext(List<SearchResult> pages) {
        StringBuilder context = new StringBuilder();
        int source = 1;
        int used = 0;
        int limit = Math.max(1, properties.maxContextLength());

        for (SearchResult page : pages) {
            String text = page.pageContent();
            if (text == null || text.isBlank()) {
                continue; // 没抓到正文的页面直接跳过
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
