package com.example.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.net.http.*;
import java.net.URI;

public class BatchServlet extends HttpServlet {
    private ObjectMapper mapper = new ObjectMapper();
    private ExecutorService workerPool;
    private HttpClient httpClient;

    @Override
    public void init() {
        ServletContext ctx = getServletContext();
        Object pool = ctx.getAttribute("workerPool");
        if (pool instanceof ExecutorService) workerPool = (ExecutorService) pool;
        else workerPool = Executors.newFixedThreadPool(10);
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    static class BatchRequest {
        public String batchId;
        public List<Map<String,Object>> requests;
    }

    static class ItemResult {
        public String id;
        public int status;
        public Object body;
        public String error;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        // --- MINIMAL FIX: Capture request info before async threads ---
        final String scheme = req.getScheme();
        final String serverName = req.getServerName();
        final int serverPort = req.getServerPort();
        // ---------------------------------------------------------------

        AsyncContext asyncCtx = req.startAsync();
        asyncCtx.setTimeout(0);

        BatchRequest batch;
        try {
            batch = mapper.readValue(req.getInputStream(), BatchRequest.class);
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"error\":\"invalid json\"}");
            asyncCtx.complete();
            return;
        }

        if (batch.requests == null || batch.requests.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"error\":\"empty requests\"}");
            asyncCtx.complete();
            return;
        }

        final int MAX_BATCH = 200;
        if (batch.requests.size() > MAX_BATCH) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"error\":\"batch too large\"}");
            asyncCtx.complete();
            return;
        }

        List<CompletableFuture<ItemResult>> futures = new ArrayList<>();
        for (Map<String,Object> item : batch.requests) {
            String id = (String) item.getOrDefault("id", UUID.randomUUID().toString());
            String method = (String) item.getOrDefault("method", "POST");
            String path = (String) item.getOrDefault("path", "/batch-demo/api/internal/compute");
            Object bodyObj = item.get("body");

            CompletableFuture<ItemResult> cf = CompletableFuture.supplyAsync(() -> {
                ItemResult r = new ItemResult();
                r.id = id;
                try {
                    if (path.startsWith("/batch-demo/api/internal")) {
                        // --- MINIMAL FIX: Use captured request info ---
                        String url = scheme + "://" + serverName + ":" + serverPort + path;
                        // -------------------------------------------------
                        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .timeout(java.time.Duration.ofSeconds(5));

                        String bodyJson = bodyObj == null ? "{}" : mapper.writeValueAsString(bodyObj);
                        if ("POST".equalsIgnoreCase(method)) {
                            builder.POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                                   .header("Content-Type", "application/json");
                        } else {
                            builder.GET();
                        }

                        HttpResponse<String> hr = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                        r.status = hr.statusCode();
                        try {
                            r.body = mapper.readValue(hr.body(), Object.class);
                        } catch (Exception ex) {
                            r.body = hr.body();
                        }
                    } else {
                        r.status = 400;
                        r.error = "unsupported path in demo: " + path;
                    }
                } catch (Exception e) {
                    r.status = 500;
                    r.error = e.getMessage();
                }
                return r;
            }, workerPool);

            futures.add(cf);
        }

        final long overallTimeoutMs = 20000L;

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        CompletableFuture<List<ItemResult>> aggregated = all.thenApply(v -> {
            List<ItemResult> results = new ArrayList<>();
            for (CompletableFuture<ItemResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (Exception e) {
                    ItemResult ir = new ItemResult();
                    ir.status = 500;
                    ir.error = e.getMessage();
                    results.add(ir);
                }
            }
            return results;
        });

        CompletableFuture.runAsync(() -> {
            HttpServletResponse asyncResp = (HttpServletResponse) asyncCtx.getResponse();
            try {
                List<ItemResult> results;
                try {
                    results = aggregated.get(overallTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    results = new ArrayList<>();
                    for (CompletableFuture<ItemResult> f : futures) {
                        if (f.isDone()) {
                            try { results.add(f.get()); } catch (Exception e) {
                                ItemResult ir = new ItemResult(); ir.status = 500; ir.error = e.getMessage(); results.add(ir);
                            }
                        } else {
                            ItemResult ir = new ItemResult();
                            ir.status = 504;
                            ir.error = "timeout";
                            results.add(ir);
                        }
                    }
                }

                Map<String,Object> out = new HashMap<>();
                out.put("batchId", batch.batchId);
                out.put("results", results);
                out.put("finishedAt", java.time.Instant.now().toString());

                asyncResp.setContentType("application/json");
                asyncResp.setStatus(HttpServletResponse.SC_OK);
                mapper.writeValue(asyncResp.getWriter(), out);

            } catch (Exception e) {
                try {
                    asyncResp.setContentType("application/json");
                    asyncResp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    Map<String,String> err = Collections.singletonMap("error", e.getMessage());
                    mapper.writeValue(asyncResp.getWriter(), err);
                } catch (IOException ignored) {}
            } finally {
                asyncCtx.complete();
            }
        }, workerPool);
    }
}
