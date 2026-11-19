package com.pmart.product.content.application;

import com.pmart.product.content.infrastructure.DraftStore;
import com.pmart.product.content.infrastructure.DraftStore.ImageRow;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 축소 이미지 미리 만들기(2026-11-20, 상품 상세 느림 — 사용자 "응"). 처음 보는 상품은 설명 사진 16~20장의 축소본을 요청 때 만들어 0.7~1.5초 늦었다
 * → 올린 이미지 내용(sha256)마다 240·480·720 축소본을 뒤에서 미리 만들어 둔다. 기록은 {@code content_image_variant}.
 *
 * <p>뒤에서 한 줄(스레드 하나)로만 돈다 — 요청 처리와 CPU 를 다투지 않게. 기동 30초 뒤 한 번, 그 뒤 2분마다, 이미지를 올린 뒤 5초 뒤(커밋을 기다림) 남은
 * 것을 다 만든다. 한 이미지가 실패하면(원본 없음 등) 경고만 남기고 다음으로 — 그 내용은 다음 차례에 다시 해 본다.
 */
@Service
public class VariantPreparer implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(VariantPreparer.class);
  private static final int BATCH = 20;

  private final DraftStore drafts;
  private final ImageVariants variants;
  private final Clock clock;
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "image-variant-prep");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          });
  private final AtomicBoolean running = new AtomicBoolean();

  public VariantPreparer(DraftStore drafts, ImageVariants variants, Clock clock) {
    this.drafts = drafts;
    this.variants = variants;
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    worker.scheduleWithFixedDelay(this::runSafely, 30, 120, TimeUnit.SECONDS);
  }

  /** 이미지를 올린 뒤 — 커밋을 기다렸다가 남은 것을 만든다. */
  public void afterUpload() {
    worker.schedule(this::runSafely, 5, TimeUnit.SECONDS);
  }

  private void runSafely() {
    try {
      runOnce();
    } catch (Throwable e) { // Error 도 — 주기 작업은 예외로 끝나면 다시 돌지 않는다(2026-11-20 리뷰 지적 1)
      log.warn("image variant prep stopped: {}", e.toString());
    }
  }

  /** 남은 것을 다 만든다. 만든 축소본 수. 동시에 두 번 돌지 않는다. */
  public int runOnce() {
    if (!running.compareAndSet(false, true)) {
      return 0;
    }
    long started = System.nanoTime();
    int made = 0;
    int contents = 0;
    try {
      java.util.Set<String> failed = new java.util.HashSet<>();
      while (true) {
        List<ImageRow> batch =
            drafts.imagesWithoutVariants(BATCH + failed.size()).stream()
                .filter(r -> !failed.contains(r.sha256()))
                .limit(BATCH)
                .toList();
        if (batch.isEmpty()) {
          break;
        }
        for (ImageRow row : batch) {
          try {
            made += variants.prepare(row);
            drafts.markVariantsPrepared(row.sha256(), clock.instant());
            contents++;
          } catch (RuntimeException e) {
            failed.add(row.sha256());
            log.warn("image variant prep failed image={} : {}", row.imageId(), e.toString());
          }
        }
      }
    } finally {
      running.set(false);
    }
    if (contents > 0) {
      log.info(
          "image variants prepared contents={} made={} in {}s",
          contents,
          made,
          Duration.ofNanos(System.nanoTime() - started).toSeconds());
    }
    return made;
  }

  @Override
  public void destroy() {
    worker.shutdownNow();
  }
}
