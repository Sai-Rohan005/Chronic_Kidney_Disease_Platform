package services;

import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.*;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.timelimiter.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PredictionRuntime implements AutoCloseable {
    final PredictionSettings settings;
    private final ExecutorService outbound;
    private final ExecutorService images;
    private final ConcurrentMap<String, CircuitBreaker> circuits = new ConcurrentHashMap<>();
    private final TimeLimiter timeLimiter;
    private final Object[] appointmentLocks = new Object[64];

    public PredictionRuntime(PredictionSettings settings) {
        settings.validate(); this.settings = settings;
        outbound = pool(settings.getOutboundConcurrency(), settings.getQueueCapacity(), "prediction-http-");
        images = pool(settings.getImageConcurrency(), settings.getQueueCapacity(), "prediction-image-");
        timeLimiter = TimeLimiter.of(Duration.ofMillis(settings.getTimeoutMillis()));
        Arrays.setAll(appointmentLocks, i -> new Object());
    }
    private static ExecutorService pool(int size, int capacity, String name) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().daemon().name(name, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    Object lock(String appointmentId) { return appointmentLocks[Math.floorMod(appointmentId.hashCode(), appointmentLocks.length)]; }

    // Capture identity per task; never use a globally inheritable security context with reused threads.
    private static <T> Callable<T> withCaller(Callable<T> work) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return () -> {
            var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            try { return work.call(); } finally { SecurityContextHolder.clearContext(); }
        };
    }
    public <T> T call(String dependency, boolean idempotent, Callable<T> work) {
        CircuitBreaker breaker = circuits.computeIfAbsent(dependency, name -> CircuitBreaker.of(name,
                CircuitBreakerConfig.custom().slidingWindowSize(settings.getCircuitMinimumCalls())
                        .minimumNumberOfCalls(settings.getCircuitMinimumCalls()).failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMillis(settings.getCircuitOpenMillis()))
                        .ignoreException(e -> e instanceof ResponseStatusException
                                || e instanceof UpstreamException u && u.status() < 500).build()));
        Retry retry = Retry.of(dependency, RetryConfig.custom().maxAttempts(idempotent ? settings.getAttempts() : 1)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(settings.getBackoffMillis(), 2))
                .retryOnException(e -> e instanceof UpstreamException u && u.retryable()).build());
        Callable<T> attempt = () -> {
            try { return timeLimiter.executeFutureSupplier(() -> outbound.submit(withCaller(work))); }
            catch (Exception exception) { throw classify(dependency, exception); }
        };
        try { return Retry.decorateCallable(retry, CircuitBreaker.decorateCallable(breaker, attempt)).call(); }
        catch (CallNotPermittedException | RejectedExecutionException exception) {
            throw new UpstreamException(503, false, dependency + " is temporarily unavailable", exception);
        } catch (UpstreamException | ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw classify(dependency, exception); }
    }
    public <T> List<T> images(List<Callable<T>> work) {
        List<CompletableFuture<T>> futures = new ArrayList<>();
        List<Future<?>> workers = new ArrayList<>();
        try {
            for (Callable<T> task : work) {
                Callable<T> captured = withCaller(task);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try { return captured.call(); }
                    catch (Exception exception) { throw new CompletionException(exception); }
                }, command -> workers.add(images.submit(command))));
            }
            List<T> results = new ArrayList<>();
            for (CompletableFuture<T> future : futures) results.add(future.get());
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(503, false, "Image prediction interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new UpstreamException(502, false, "Image prediction failed", cause);
        } catch (RejectedExecutionException exception) {
            throw new UpstreamException(503, false, "Image prediction capacity exhausted", exception);
        } finally {
            // CompletableFuture.cancel does not interrupt its supplier; cancel the actual executor tasks too.
            futures.forEach(future -> future.cancel(true));
            workers.forEach(worker -> worker.cancel(true));
        }
    }
    private static RuntimeException classify(String dependency, Exception exception) {
        Throwable cause = exception;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof UpstreamException classified) return classified;
        if (cause instanceof ResponseStatusException status) return status;
        if (cause instanceof RestClientResponseException http) {
            int status = http.getStatusCode().value();
            return new UpstreamException(status < 500 ? status : status == 503 || status == 504 ? 503 : 502,
                    status >= 500, dependency + " returned HTTP " + status, http);
        }
        if (cause instanceof TimeoutException || cause instanceof IOException || cause instanceof ResourceAccessException
                || cause instanceof RejectedExecutionException || cause instanceof InterruptedException) {
            return new UpstreamException(503, !(cause instanceof RejectedExecutionException), dependency + " is temporarily unavailable", cause);
        }
        return new UpstreamException(502, false, dependency + " returned invalid data", cause);
    }
    @Override @PreDestroy public void close() { images.shutdownNow(); outbound.shutdownNow(); }
}
