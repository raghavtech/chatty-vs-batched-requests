package com.example.batch;

import javax.servlet.*;
import java.util.concurrent.*;

public class WebAppContextListener implements ServletContextListener {
    private ThreadPoolExecutor workerPool;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();
        int core = 8;
        int max = 40;
        long keepAlive = 60L;
        // bounded queue to avoid OOM under heavy load
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(500);
        workerPool = new ThreadPoolExecutor(core, max, keepAlive, TimeUnit.SECONDS, queue,
            r -> {
                Thread t = new Thread(r, "batch-worker-" + ThreadLocalRandom.current().nextInt(1000));
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy() // reject when queue full
        );

        // option: prestart core threads
        workerPool.prestartAllCoreThreads();

        ctx.setAttribute("workerPool", workerPool);
        ctx.log("Worker pool initialized: core=" + core + " max=" + max + " queue=500");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();
        ctx.log("Shutting down worker pool...");
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
