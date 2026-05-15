package com.android.dialer.callrecord;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.AutoCallRecordingContactResolver.ResolveResult;
import com.android.dialer.callrecord.AutoCallRecordingContactResolver.ResolvedSelectedNumber;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AutoCallRecordingContactResolverTest {

  private static final String LOCAL_NUMBER = "+15551230001";
  private static final String OTHER_LOCAL_NUMBER = "+15551230002";

  @Test
  public void selectedNumberLookupRunsNumbersInParallel() {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch bothLookupsStarted = new CountDownLatch(2);
    AtomicInteger activeLookups = new AtomicInteger();
    AtomicInteger maxActiveLookups = new AtomicInteger();
    try {
      ResolveResult result =
          AutoCallRecordingContactResolver.resolveSelectedNumbersForTesting(
              setOf(LOCAL_NUMBER, OTHER_LOCAL_NUMBER),
              executor,
              number -> {
                int activeCount = activeLookups.incrementAndGet();
                maxActiveLookups.updateAndGet(current -> Math.max(current, activeCount));
                bothLookupsStarted.countDown();
                try {
                  bothLookupsStarted.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                } finally {
                  activeLookups.decrementAndGet();
                }
                return ResolvedSelectedNumber.createUnresolved(number);
              });

      assertThat(maxActiveLookups.get()).isAtLeast(2);
      assertThat(result.lookupSucceeded).isTrue();
      assertThat(result.resolvedNumbers.keySet())
          .containsExactly(LOCAL_NUMBER, OTHER_LOCAL_NUMBER);
    } finally {
      executor.shutdownNow();
    }
  }

  private static Set<String> setOf(String... values) {
    return new HashSet<>(Arrays.asList(values));
  }
}
