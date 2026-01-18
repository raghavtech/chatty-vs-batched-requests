package com.example.batch;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.ServletException;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class InternalProcessServlet extends HttpServlet {
    private ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Very small simulation: echo with a small delay based on input
        Map<String,Object> reqBody = mapper.readValue(req.getInputStream(), Map.class);

        // simulate variable work (avoid long sleeps in prod)
        try {
            Object v = reqBody.get("value");
            int delay = 200;
            if (v instanceof Number) delay = Math.min(1000, 50 + ((Number) v).intValue() % 500);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String,Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("input", reqBody);
        out.put("processedAt", System.currentTimeMillis());

        resp.setContentType("application/json");
        resp.setStatus(HttpServletResponse.SC_OK);
        mapper.writeValue(resp.getWriter(), out);
    }
}
