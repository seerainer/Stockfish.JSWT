package io.github.seerainer.chess.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Resource manager for proper cleanup of AI executor threads.
 */
public class ResourceManager implements AutoCloseable {

    private final ExecutorService executorService;
    private volatile boolean closed = false;

    public ResourceManager() {
	this.executorService = Executors.newSingleThreadExecutor(r -> {
	    final var t = new Thread(r, "Chess-AI-Worker");
	    t.setDaemon(true);
	    return t;
	});
    }

    @Override
    public void close() {
	if (closed) {
	    return;
	}

	closed = true;
	executorService.shutdown();

	try {
	    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
		System.err.println("AI threads did not terminate gracefully, forcing shutdown");
		executorService.shutdownNow();
		if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
		    System.err.println("AI threads did not terminate after forced shutdown");
		}
	    }
	} catch (final InterruptedException e) {
	    executorService.shutdownNow();
	    Thread.currentThread().interrupt();
	}
    }

    /**
     * Returns the executor service for running AI computations asynchronously.
     *
     * @return the executor service
     * @throws IllegalStateException if this manager has already been closed
     */
    public ExecutorService getExecutorService() {
	if (closed) {
	    throw new IllegalStateException("ResourceManager has been closed");
	}
	return executorService;
    }
}
