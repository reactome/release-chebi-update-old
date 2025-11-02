package org.reactome.release.chebiupdate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

public class ChEBIAPIClient {
    private static final String BASE_URL = "https://www.ebi.ac.uk/chebi/backend/api/public/compounds/";

    private static final int MAX_RETRIES = 3;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long INITIAL_BACKOFF_MS = 1000;

    private static final Logger logger = LogManager.getLogger(ChEBIAPIClient.class);

    private final HttpClient httpClient;

    public ChEBIAPIClient() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    // For testing
    ChEBIAPIClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public JSONObject fetchCompounds(Set<String> chEBIIdentifiers) throws IOException, InterruptedException {
        if (chEBIIdentifiers == null || chEBIIdentifiers.isEmpty()) {
            throw new IllegalArgumentException("No identifiers to fetch from ChEBI");
        }

        int attemptCount = 0;
        long backoffTime = INITIAL_BACKOFF_MS;

        while (true) {
            try {
                return executeRequest(chEBIIdentifiers);
            } catch (IOException | InterruptedException e) {
                attemptCount++;

                if (attemptCount >= MAX_RETRIES) {
                    logger.error("Failed to fetch compounds after {} attempts", MAX_RETRIES);
                    throw e;
                }

                logger.warn("Attempt {} failed, retrying in {} ms", attemptCount, backoffTime, e);
                Thread.sleep(backoffTime);
                backoffTime = (long) (backoffTime * BACKOFF_MULTIPLIER);
            }
        }
    }

    private JSONObject executeRequest(Set<String> chEBIIdentifiers) throws IOException, InterruptedException {
        JSONObject payload = new JSONObject();
        payload.put("chebi_ids", new JSONArray(chEBIIdentifiers));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (isRetryableStatusCode(response.statusCode())) {
            throw new IOException("Received retryable status code: " + response.statusCode());
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Unable to retrieve ChEBI entity/entities from RESTful API: " + response);
        }

        return new JSONObject(response.body());
    }

    private boolean isRetryableStatusCode(int statusCode) {
        final int tooManyRequests = 429;
        final int requestTimeOut = 408;
        final int serverErrorLowerLimit = 500;
        final int serverErrorUpperLimit = 600;

        // Retry on server errors and specific client errors
        return statusCode == tooManyRequests ||
               statusCode == requestTimeOut ||
               (statusCode >= serverErrorLowerLimit && statusCode < serverErrorUpperLimit);
    }
}