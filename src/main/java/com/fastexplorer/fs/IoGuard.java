package com.fastexplorer.fs;

import com.fastexplorer.Messages;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Runs a blocking filesystem call on {@code pool} and stops waiting when the user navigates away
 * or the timeout elapses. A native call stuck in {@code FindFirstFile} cannot be interrupted;
 * the waiting thread returns anyway and the stuck call lives on a daemon thread.
 */
public final class IoGuard {
    private IoGuard() {
    }

    public static <T> T call(
            ExecutorService pool,
            Callable<T> task,
            long timeoutMillis,
            BooleanSupplier cancelled
    ) throws ListingException {
        Future<T> future = pool.submit(task);
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    future.cancel(true);
                    throw new ListingException(ListingException.Kind.CANCELLED, "中止しました");
                }
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    future.cancel(true);
                    throw new ListingException(ListingException.Kind.TIMEOUT, Messages.ERR_TIMEOUT);
                }
                try {
                    return future.get(Math.min(remain, 200L), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Poll cancellation and the deadline again.
                }
            }
        } catch (ListingException e) {
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ListingException(ListingException.Kind.CANCELLED, "中止しました", e);
        } catch (CancellationException e) {
            throw new ListingException(ListingException.Kind.CANCELLED, "中止しました", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ListingException listing) {
                throw listing;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw NioDirectoryLister.map(cause);
        }
    }
}
