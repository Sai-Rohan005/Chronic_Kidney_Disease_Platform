package services;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class PredictionRuntimeTest {
    @Test void imageWorkIsConcurrentBoundedOrderedAndPropagatesCaller() {
        var settings = new PredictionSettings(); settings.setImageConcurrency(2);
        var started = new CountDownLatch(2); var active = new AtomicInteger(); var peak = new AtomicInteger();
        org.slf4j.MDC.put("requestId", "concurrent-images");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("doctor", "token"));
        try (var runtime = new PredictionRuntime(settings)) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int index = i;
                tasks.add(() -> {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        assertEquals("concurrent-images", org.slf4j.MDC.get("requestId"));
                        assertEquals("doctor", SecurityContextHolder.getContext().getAuthentication().getName());
                        started.countDown(); assertTrue(started.await(2, TimeUnit.SECONDS), "Two images must start concurrently");
                        return index;
                    } finally { active.decrementAndGet(); }
                });
            }
            assertEquals(List.of(0, 1, 2, 3), runtime.images(tasks));
            assertEquals(2, peak.get());
            assertEquals("concurrent-images", org.slf4j.MDC.get("requestId"));
            SecurityContextHolder.clearContext(); org.slf4j.MDC.clear();
            assertEquals(List.of(true), runtime.images(List.of(() -> SecurityContextHolder.getContext().getAuthentication() == null && org.slf4j.MDC.get("requestId") == null)));
        } finally { SecurityContextHolder.clearContext(); org.slf4j.MDC.clear(); }
    }
    @Test void invalidConfigurationFailsBeforeStartingWork() {
        for (double weight : List.of(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            var settings = new PredictionSettings(); settings.setTabularWeight(weight);
            assertThrows(IllegalArgumentException.class, () -> new PredictionRuntime(settings));
        }
        var settings = new PredictionSettings(); settings.setThreshold(1.1);
        assertThrows(IllegalArgumentException.class, () -> new PredictionRuntime(settings));
        settings.setThreshold(0.5); settings.setTabularWeight(0); settings.setImageWeight(0);
        assertThrows(IllegalArgumentException.class, () -> new PredictionRuntime(settings));
    }
    @Test void onlyExactHttpsHostWithoutUserInfoFragmentOrAlternatePortIsAllowed() {
        var settings = new PredictionSettings();
        assertTrue(ImageUrlPolicy.allowed("https://res.cloudinary.com/demo/a.png", settings));
        for (String url : List.of("http://res.cloudinary.com/a", "https://res.cloudinary.com.evil.invalid/a",
                "https://res.cloudinary.com@127.0.0.1/a", "https://127.0.0.1/a", "file:///tmp/a",
                "https://res.cloudinary.com:8443/a", "https://user@res.cloudinary.com/a", "https://res.cloudinary.com/a#x")) {
            assertFalse(ImageUrlPolicy.allowed(url, settings), url);
        }
    }
}
