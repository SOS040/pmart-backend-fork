package com.pmart.review.application;

import com.pmart.review.infrastructure.ReviewStore;
import com.pmart.review.infrastructure.ReviewStore.PhotoRow;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 리뷰 사진 축소본 미리 만들기(2026-11-20, 계약 0.4.0). 사진을 올린 5초 뒤(커밋을 기다림)·기동 30초 뒤·2분마다 축소본이 없는 사진을 차례로 만든다.
 * 스레드 하나·낮은 우선순위 — 요청 처리와 CPU 를 다투지 않게. 한 장이 실패하면 경고만 남기고 다음으로(그 사진은 다음 차례에 다시).
 */
@Component
public class ReviewPhotoPreparer implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ReviewPhotoPreparer.class);
  private static final int BATCH = 20;

  private final ReviewStore store;
  private final ReviewPhotoVariants variants;
  private final Clock clock;
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "review-photo-prep");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          });
  private final AtomicBoolean running = new AtomicBoolean();

  public ReviewPhotoPreparer(ReviewStore store, ReviewPhotoVariants variants, Clock clock) {
    this.store = store;
    this.variants = variants;
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    worker.scheduleWithFixedDelay(this::runSafely, 30, 120, TimeUnit.SECONDS);
  }

  /** 사진을 올린 뒤 — 커밋을 기다렸다가 만든다. */
  public void afterUpload() {
    worker.schedule(this::runSafely, 5, TimeUnit.SECONDS);
  }

  private void runSafely() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.warn("리뷰 사진 축소본 만들기 멈춤 — 다음에 다시: {}", e.toString());
    }
  }

  /** 남은 것을 다 만든다. 처리한 사진 수. 동시에 두 번 돌지 않는다. */
  public int runOnce() {
    if (!running.compareAndSet(false, true)) {
      return 0;
    }
    int done = 0;
    int made = 0;
    try {
      Set<String> failed = new HashSet<>();
      while (true) {
        List<PhotoRow> batch =
            store.photosWithoutVariants(BATCH + failed.size()).stream()
                .filter(p -> !failed.contains(p.photoId()))
                .limit(BATCH)
                .toList();
        if (batch.isEmpty()) {
          break;
        }
        for (PhotoRow p : batch) {
          try {
            made += variants.prepare(p);
            store.markVariants(p.photoId(), clock.instant());
            done++;
          } catch (RuntimeException e) {
            failed.add(p.photoId());
            log.warn("리뷰 사진 {} 축소본 실패: {}", p.photoId(), e.toString());
          }
        }
      }
    } finally {
      running.set(false);
    }
    if (done > 0) {
      log.info("리뷰 사진 축소본 photos={} made={}", done, made);
    }
    return done;
  }

  @Override
  public void destroy() {
    worker.shutdownNow();
  }
}
