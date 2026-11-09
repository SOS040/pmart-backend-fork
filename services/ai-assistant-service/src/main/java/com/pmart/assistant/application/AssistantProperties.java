package com.pmart.assistant.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.assistant.*}. 값의 정본은 설정 저장소 {@code ai-assistant-service.yml}(ADR-0029).
 *
 * <p>정책 미정 항목은 설정이 없으면 동작하지 않는 쪽으로 둔다: 답변 보존 기간이 없으면 지우지 않는다, 질문 원문은 기본 저장하지 않는다.
 */
@ConfigurationProperties("pmart.assistant")
public class AssistantProperties {

  private Model model = new Model();
  private Processing processing = new Processing();
  private Retrieval retrieval = new Retrieval();
  private Answers answers = new Answers();
  private Ingest ingest = new Ingest();

  public static class Model {
    /**
     * local(기본: 해시 임베딩 + 발췌 답변) | ollama(LAN Ollama 임베딩·생성, ADR-0051) | fake(시험: 주입한 ChatModel 로 생성
     * 경로).
     */
    private String provider = "local";

    private int embeddingDim = 384;

    private Ollama ollama = new Ollama();

    public Ollama getOllama() {
      return ollama;
    }

    public void setOllama(Ollama ollama) {
      this.ollama = ollama;
    }

    public String getProvider() {
      return provider;
    }

    public void setProvider(String provider) {
      this.provider = provider;
    }

    public int getEmbeddingDim() {
      return embeddingDim;
    }

    public void setEmbeddingDim(int embeddingDim) {
      this.embeddingDim = embeddingDim;
    }
  }

  /** provider=ollama 설정(ADR-0051 결정 6). 모델 이름·버전이 없으면 기동하지 않는다(대체값 없음). */
  public static class Ollama {
    private @Nullable String baseUrl;
    private @Nullable String embeddingModel;

    /** Ollama 모델 ID(ollama list). 스냅숏·캐시 키에 들어간다 — 모델 파일을 바꾸면 이 값도 바꾸고 재색인한다. */
    private @Nullable String embeddingVersion;

    /** 질문에만 붙이는 지시문(Qwen3-Embedding 권장 형식). 바꾸면 재색인. */
    private @Nullable String queryInstruction;

    private @Nullable String chatModel;
    private double chatTemperature = 0.2;
    private int chatNumCtx = 8192;

    /** Qwen3 의 생각(thinking) 단계를 끈다 — 지연을 줄인다. */
    private boolean disableThinking = true;

    private String keepAlive = "24h";
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 한 번의 모델 호출 시간 제한. 처리 하드 타임아웃보다 짧아야 한다(기동 검사). */
    private Duration requestTimeout = Duration.ofSeconds(25);

    public @Nullable String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(@Nullable String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public @Nullable String getEmbeddingModel() {
      return embeddingModel;
    }

    public void setEmbeddingModel(@Nullable String embeddingModel) {
      this.embeddingModel = embeddingModel;
    }

    public @Nullable String getEmbeddingVersion() {
      return embeddingVersion;
    }

    public void setEmbeddingVersion(@Nullable String embeddingVersion) {
      this.embeddingVersion = embeddingVersion;
    }

    public @Nullable String getQueryInstruction() {
      return queryInstruction;
    }

    public void setQueryInstruction(@Nullable String queryInstruction) {
      this.queryInstruction = queryInstruction;
    }

    public @Nullable String getChatModel() {
      return chatModel;
    }

    public void setChatModel(@Nullable String chatModel) {
      this.chatModel = chatModel;
    }

    public double getChatTemperature() {
      return chatTemperature;
    }

    public void setChatTemperature(double chatTemperature) {
      this.chatTemperature = chatTemperature;
    }

    public int getChatNumCtx() {
      return chatNumCtx;
    }

    public void setChatNumCtx(int chatNumCtx) {
      this.chatNumCtx = chatNumCtx;
    }

    public boolean isDisableThinking() {
      return disableThinking;
    }

    public void setDisableThinking(boolean disableThinking) {
      this.disableThinking = disableThinking;
    }

    public String getKeepAlive() {
      return keepAlive;
    }

    public void setKeepAlive(String keepAlive) {
      this.keepAlive = keepAlive;
    }

    public Duration getConnectTimeout() {
      return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
      return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
    }
  }

  public static class Processing {
    /** 처리 하드 타임아웃. 반드시 lease 보다 짧다(기동 검사). */
    private Duration timeout = Duration.ofSeconds(60);

    /** 선점 lease. 만료되면 같은 키 재요청이 다시 선점한다. */
    private Duration lease = Duration.ofSeconds(120);

    /** 202 응답의 Retry-After(초). */
    private int retryAfterSeconds = 2;

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }

    public Duration getLease() {
      return lease;
    }

    public void setLease(Duration lease) {
      this.lease = lease;
    }

    public int getRetryAfterSeconds() {
      return retryAfterSeconds;
    }

    public void setRetryAfterSeconds(int retryAfterSeconds) {
      this.retryAfterSeconds = retryAfterSeconds;
    }
  }

  public static class Retrieval {
    /** SQL 이 돌려줄 후보 수(임계값·가중 전). */
    private int candidates = 40;

    /** 최종 근거 수. */
    private int topK = 5;

    /** 혼합 유사도 하한. 기준선 평가(RetrievalEvalIT)로 정한다. */
    private double threshold = 0.18;

    private double cosineWeight = 0.6;
    private double keywordWeight = 0.4;

    /** 우선순위 등급(domain-map.md:35) 1단계당 가중. */
    private double precedenceWeight = 0.01;

    /** 지위 가중(확정 > 채택 > 설계안 > 가정 > 미결정·외부). */
    private double confirmedWeight = 0.06;

    private double adoptedWeight = 0.04;
    private double proposalWeight = 0.02;
    private double assumptionWeight = 0.01;

    /** 발췌 답변 길이 한도 — 온전한 청크 개수를 줄여서 맞춘다. */
    private int maxAnswerChars = 4000;

    private int maxChunkChars = 1500;

    /** 생성 답변 문장별 근거 bigram 겹침 하한(보조 휴리스틱). */
    private double minSentenceOverlap = 0.5;

    public int getCandidates() {
      return candidates;
    }

    public void setCandidates(int candidates) {
      this.candidates = candidates;
    }

    public int getTopK() {
      return topK;
    }

    public void setTopK(int topK) {
      this.topK = topK;
    }

    public double getThreshold() {
      return threshold;
    }

    public void setThreshold(double threshold) {
      this.threshold = threshold;
    }

    public double getCosineWeight() {
      return cosineWeight;
    }

    public void setCosineWeight(double cosineWeight) {
      this.cosineWeight = cosineWeight;
    }

    public double getKeywordWeight() {
      return keywordWeight;
    }

    public void setKeywordWeight(double keywordWeight) {
      this.keywordWeight = keywordWeight;
    }

    public double getPrecedenceWeight() {
      return precedenceWeight;
    }

    public void setPrecedenceWeight(double precedenceWeight) {
      this.precedenceWeight = precedenceWeight;
    }

    public double getConfirmedWeight() {
      return confirmedWeight;
    }

    public void setConfirmedWeight(double confirmedWeight) {
      this.confirmedWeight = confirmedWeight;
    }

    public double getAdoptedWeight() {
      return adoptedWeight;
    }

    public void setAdoptedWeight(double adoptedWeight) {
      this.adoptedWeight = adoptedWeight;
    }

    public double getProposalWeight() {
      return proposalWeight;
    }

    public void setProposalWeight(double proposalWeight) {
      this.proposalWeight = proposalWeight;
    }

    public double getAssumptionWeight() {
      return assumptionWeight;
    }

    public void setAssumptionWeight(double assumptionWeight) {
      this.assumptionWeight = assumptionWeight;
    }

    public int getMaxAnswerChars() {
      return maxAnswerChars;
    }

    public void setMaxAnswerChars(int maxAnswerChars) {
      this.maxAnswerChars = maxAnswerChars;
    }

    public int getMaxChunkChars() {
      return maxChunkChars;
    }

    public void setMaxChunkChars(int maxChunkChars) {
      this.maxChunkChars = maxChunkChars;
    }

    public double getMinSentenceOverlap() {
      return minSentenceOverlap;
    }

    public void setMinSentenceOverlap(double minSentenceOverlap) {
      this.minSentenceOverlap = minSentenceOverlap;
    }
  }

  public static class Answers {
    /** 질문 원문 저장(기본 false — request_hash 만). [결정 필요] */
    private boolean storeQuestion = false;

    /** 답변 보존 기간. 비면 지우지 않는다. [결정 필요] */
    private @Nullable Duration retention;

    public boolean isStoreQuestion() {
      return storeQuestion;
    }

    public void setStoreQuestion(boolean storeQuestion) {
      this.storeQuestion = storeQuestion;
    }

    public @Nullable Duration getRetention() {
      return retention;
    }

    public void setRetention(@Nullable Duration retention) {
      this.retention = retention;
    }
  }

  public static class Ingest {
    /** true 면 기동 뒤 한 번 색인하고 종료(Gradle ingestKnowledge). 평소 기동에서는 색인하지 않는다. */
    private boolean run = false;

    /** true 면 기동 뒤 스냅숏 정리 한 번 하고 종료(Gradle pruneSnapshots). */
    private boolean prune = false;

    /** 색인할 저장소 경로(-Prepo). */
    private @Nullable String repo;

    /** 스냅숏 정리 유예. lease×2 이상이어야 한다(기동 검사). */
    private Duration pruneGrace = Duration.ofHours(1);

    public boolean isRun() {
      return run;
    }

    public void setRun(boolean run) {
      this.run = run;
    }

    public boolean isPrune() {
      return prune;
    }

    public void setPrune(boolean prune) {
      this.prune = prune;
    }

    public @Nullable String getRepo() {
      return repo;
    }

    public void setRepo(@Nullable String repo) {
      this.repo = repo;
    }

    public Duration getPruneGrace() {
      return pruneGrace;
    }

    public void setPruneGrace(Duration pruneGrace) {
      this.pruneGrace = pruneGrace;
    }
  }

  public Model getModel() {
    return model;
  }

  public void setModel(Model model) {
    this.model = model;
  }

  public Processing getProcessing() {
    return processing;
  }

  public void setProcessing(Processing processing) {
    this.processing = processing;
  }

  public Retrieval getRetrieval() {
    return retrieval;
  }

  public void setRetrieval(Retrieval retrieval) {
    this.retrieval = retrieval;
  }

  public Answers getAnswers() {
    return answers;
  }

  public void setAnswers(Answers answers) {
    this.answers = answers;
  }

  public Ingest getIngest() {
    return ingest;
  }

  public void setIngest(Ingest ingest) {
    this.ingest = ingest;
  }
}
