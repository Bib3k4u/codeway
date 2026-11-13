package com.codeway.ai;

import com.codeway.service.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Thin OpenAI-compatible chat client. Azure style when AI_API_VERSION is set, plain OpenAI otherwise. */
@Component
public class AiClient {
    @Value("${codeway.ai.endpoint:}") private String endpoint;
    @Value("${codeway.ai.api-key:}") private String apiKey;
    @Value("${codeway.ai.api-version:}") private String apiVersion;
    @Value("${codeway.ai.model:}") private String model;
    @Value("${codeway.ai.hint-model:}") private String hintModel;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final AiQuota quota;

    public AiClient(AiQuota quota) { this.quota = quota; }

    /** Thrown instead of calling the model when today's AI cap is used up; callers fall back as for any AI failure. */
    public static class QuotaExceeded extends IllegalStateException {
        public QuotaExceeded(String m) { super(m); }
    }

    public boolean isConfigured() {
        return !apiKey.isBlank() && !model.isBlank() && (!apiVersion.isBlank() ? !endpoint.isBlank() : true);
    }

    public String model() { return model; }
    public String hintModel() { return hintModel.isBlank() ? model : hintModel; }

    public String chat(String modelName, String system, String user, boolean json) {
        if (!isConfigured()) throw new IllegalStateException("AI is not configured");
        Long userId = com.codeway.auth.CurrentUser.get();
        if (!quota.tryAcquire(userId)) {
            System.err.println("[ai] daily limit reached " + quota.snapshot(userId) + ", using fallback");
            throw new QuotaExceeded("Daily AI limit reached");
        }
        try {
            boolean azure = !apiVersion.isBlank();
            String base = endpoint.isBlank() ? "https://api.openai.com/v1" : endpoint.replaceAll("/+$", "");
            String url = azure
                    ? base + "/openai/deployments/" + modelName + "/chat/completions?api-version=" + apiVersion
                    : base + "/chat/completions";
            Map<String, Object> body = new LinkedHashMap<>();
            if (!azure) body.put("model", modelName);
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            if (json) body.put("response_format", Map.of("type", "json_object"));

            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json");
            if (azure) rb.header("api-key", apiKey); else rb.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> res = http.send(rb.POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw new IllegalStateException("AI request failed (" + res.statusCode() + "): " + abbreviate(res.body()));
            JsonNode root = Json.M.readTree(res.body());
            return root.path("choices").path(0).path("message").path("content").asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI request failed: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String s) { return s.length() > 300 ? s.substring(0, 300) + "…" : s; }
}
