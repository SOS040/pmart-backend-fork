package com.pmart.assistant.application;

import com.pmart.assistant.domain.CitationVerifier;
import com.pmart.assistant.infrastructure.AnswerStore;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.assistant.infrastructure.embedding.DescribedEmbeddingModel;
import com.pmart.assistant.infrastructure.embedding.LocalHashEmbeddingModel;
import com.pmart.assistant.infrastructure.ingest.KnowledgeIngestor;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 모델 제공자 연결. {@code pmart.assistant.model.provider}:
 *
 * <ul>
 *   <li>{@code local}(기본): 해시 임베딩 + 발췌 답변 — 외부 호출 없음, 기준선.
 *   <li>{@code fake}(시험): 시험이 주입한 {@link ChatModel} 로 생성 경로(인용 검사·재생성·발췌 전환)를 돈다.
 * </ul>
 *
 * 외부 제공자 모듈은 결정 뒤 추가한다(ADR-0036 결정 필요 1). 기동 검사: 하드 타임아웃 &lt; lease, 스냅숏 정리 유예 ≥ lease×2.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfig {

  @Bean
  @ConditionalOnMissingBean
  Clock assistantClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  DescribedEmbeddingModel embeddingModel(AssistantProperties props) {
    return new LocalHashEmbeddingModel(props.getModel().getEmbeddingDim());
  }

  @Bean
  @ConditionalOnMissingBean
  AnswerGenerator answerGenerator(
      AssistantProperties props, ObjectProvider<ChatModel> chatModel, ObjectMapper json) {
    var r = props.getRetrieval();
    ExtractiveAnswerGenerator extractive =
        new ExtractiveAnswerGenerator(r.getMaxAnswerChars(), r.getTopK());
    String provider = props.getModel().getProvider();
    if ("local".equals(provider)) {
      return extractive;
    }
    if ("fake".equals(provider)) {
      ChatModel chat = chatModel.getIfAvailable();
      if (chat == null) {
        throw new IllegalStateException("provider=fake 인데 ChatModel 빈이 없습니다(시험 전용).");
      }
      return new GenerativeAnswerGenerator(
          chat, "fake-chat", new CitationVerifier(r.getMinSentenceOverlap()), extractive, json);
    }
    throw new IllegalStateException(
        "지원하지 않는 pmart.assistant.model.provider: " + provider + " (local|fake)");
  }

  /** 처리 실행기 — 작업마다 가상 스레드, 호출 스레드의 관측(trace)·MDC 를 넘긴다. */
  @Bean(destroyMethod = "shutdownNow")
  ExecutorService assistantExecutor() {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(),
        () -> ContextSnapshotFactory.builder().build().captureAll());
  }

  @Bean
  PolicyQuestionService policyQuestionService(
      AnswerStore answers,
      SnapshotStore snapshots,
      Retriever retriever,
      AnswerGenerator generator,
      DescribedEmbeddingModel embeddings,
      AccessPolicy access,
      AssistantProperties props,
      ExecutorService assistantExecutor,
      ObjectMapper json,
      Clock clock) {
    validate(props);
    return new PolicyQuestionService(
        answers,
        snapshots,
        retriever,
        generator,
        embeddings,
        access,
        props,
        assistantExecutor,
        json,
        clock);
  }

  @Bean
  KnowledgeIngestor knowledgeIngestor(
      DataSource dataSource,
      SnapshotStore snapshots,
      DescribedEmbeddingModel embeddings,
      AssistantProperties props,
      Clock clock) {
    return new KnowledgeIngestor(
        dataSource,
        snapshots,
        embeddings,
        new GitSourceRevision(),
        clock,
        props.getRetrieval().getMaxChunkChars());
  }

  static void validate(AssistantProperties props) {
    var p = props.getProcessing();
    if (p.getTimeout().compareTo(p.getLease()) >= 0) {
      throw new IllegalStateException(
          "pmart.assistant.processing.timeout("
              + p.getTimeout()
              + ") 는 lease("
              + p.getLease()
              + ") 보다 짧아야 합니다.");
    }
    if (props.getIngest().getPruneGrace().compareTo(p.getLease().multipliedBy(2)) < 0) {
      throw new IllegalStateException("pmart.assistant.ingest.prune-grace 는 lease×2 이상이어야 합니다.");
    }
  }
}
