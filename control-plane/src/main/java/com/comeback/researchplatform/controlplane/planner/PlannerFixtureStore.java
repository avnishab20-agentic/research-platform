package com.comeback.researchplatform.controlplane.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PlannerService's own fixture record/replay store -- deliberately local to
 * control-plane rather than a shared {@code common} utility. {@code common}
 * is kept dependency-light on purpose (see its pom.xml: only
 * GuardrailProperties' plain spring-boot annotations are the one documented
 * exception); a full read/record JSON-file store needs Jackson, slf4j and
 * {@code @Component}, which is a much bigger footprint than that exception
 * covers. agent-service's {@code FixtureIO} plays the same role for
 * chat/search/extract fixtures there -- this is its planner-only sibling,
 * not a refactor of it.
 */
@Component
class PlannerFixtureStore {

    private static final Logger log = LoggerFactory.getLogger(PlannerFixtureStore.class);
    private static final String FILENAME = "planner-responses.json";

    private final ObjectMapper objectMapper;
    private final String sourceDir;

    PlannerFixtureStore(ObjectMapper objectMapper,
                         @Value("${fixtures.record-dir:src/main/resources/fixtures/}") String sourceDir) {
        this.objectMapper = objectMapper;
        this.sourceDir = sourceDir;
    }

    static String keyFor(String question) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(question.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @SuppressWarnings("unchecked")
    List<String> read(String key) {
        Map<String, Object> all = loadFromClasspath();
        Object raw = all.get(key);
        if (raw == null) {
            throw new MissException(key);
        }
        return objectMapper.convertValue(raw, List.class);
    }

    synchronized void record(String key, List<String> subQuestions) {
        Path path = Path.of(sourceDir + FILENAME);
        Map<String, Object> all = loadFromDisk(path);
        all.put(key, subQuestions);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(all));
            log.info("Recorded planner fixture -> {}", key);
        } catch (IOException e) {
            log.warn("Could not write planner fixture file {}", path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadFromClasspath() {
        ClassPathResource resource = new ClassPathResource("fixtures/" + FILENAME);
        if (!resource.exists()) {
            return new LinkedHashMap<>();
        }
        try (InputStream in = resource.getInputStream()) {
            return new LinkedHashMap<>(objectMapper.readValue(in, Map.class));
        } catch (IOException e) {
            log.warn("Could not read planner fixture file", e);
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadFromDisk(Path path) {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(Files.newInputStream(path), Map.class));
        } catch (IOException e) {
            log.warn("Could not read planner fixture file {}", path, e);
            return new LinkedHashMap<>();
        }
    }

    static class MissException extends RuntimeException {
        MissException(String key) {
            super("No recorded planner fixture for key " + key);
        }
    }
}
