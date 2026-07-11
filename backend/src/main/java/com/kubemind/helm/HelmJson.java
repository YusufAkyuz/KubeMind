package com.kubemind.helm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Shared `helm ... -o json` → DTO list parsing, blank-safe. */
final class HelmJson {

    private HelmJson() {}

    static <T> List<T> parseArray(ObjectMapper mapper, String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not parse helm output: " + e.getMessage());
        }
    }
}
