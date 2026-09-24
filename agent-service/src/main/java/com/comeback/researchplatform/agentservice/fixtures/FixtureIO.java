package com.comeback.researchplatform.agentservice.fixtures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
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
import java.util.Map;

/**
 * The whole of fixture mode's storage layer: one JSON object per fixture
 * file, mapping a stable key to a recorded value. Read path (fixture-profile
 * replay) loads from the classpath, so it works from a packaged jar. Write
 * path (record mode) writes to the actual source file under
 * {@code src/main/resources/fixtures/}, not the compiled classpath copy --
 * recording is a dev-time-only workflow, and the point is for the recorded
 * fixture to land in source control, committed, for every later run (and
 * every eval) to replay for free.
 */
@Component
public class FixtureIO {

    private static final Logger log = LoggerFactory.getLogger(FixtureIO.class);

    private final ObjectMapper objectMapper;
    private final String sourceDir;
    private final boolean recordMode;

    public FixtureIO(ObjectMapper objectMapper,
                      // Relative to the process's actual working directory, which for
                      // `mvn -pl agent-service spring-boot:run` is the agent-service
                      // module's own directory, not the repo root -- found live: a
                      // wrong "agent-service/..." prefix here created a wrongly
                      // double-nested agent-service/agent-service/src/... tree instead
                      // of writing to the real source folder.
                      @Value("${fixtures.record-dir:src/main/resources/fixtures/}") String sourceDir,
                      @Value("${fixtures.record-mode:false}") boolean recordMode) {
        this.objectMapper = objectMapper;
        this.sourceDir = sourceDir;
        this.recordMode = recordMode;
    }

    /** True when {@code fixtures.record-mode} is on: live responses get saved as fixtures. */
    public boolean isRecordMode() {
        return recordMode;
    }

    /** Saves one live chat reply so fixture mode can replay it later. Does nothing
     *  unless record mode is on. Replay itself is handled by FixtureChatModel.
     *  <p>
     *  {@code promptText} must be the system and user text joined by
     *  {@code "\n---\n"}, the same way FixtureChatModel builds its lookup key.
     *  <p>
     *  Called by hand after each chat call rather than done by wrapping the
     *  ChatModel bean: a @Primary ChatModel decorator was tried and broke
     *  Spring AI's internal OpenAiChatOptions casting (ClassCastException). */
    public void recordChat(String promptText, ChatResponse response) {
        if (recordMode) {
            record("chat-responses.json", keyFor(promptText), response.getResult().getOutput().getText());
        }
    }

    /** Stable, fixed-length key for an arbitrary piece of request text (a
     *  query list, a URL, a full prompt) -- same approach as
     *  retrieval-service's cache keys, for the same reason: readable, unique,
     *  no awkward characters in a JSON key. */
    public static String keyFor(String requestText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(requestText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public <T> T read(String filename, String key, Class<T> type) {
        Map<String, Object> all = loadRaw(filename);
        Object raw = all.get(key);
        if (raw == null) {
            throw new FixtureMissException(filename, key);
        }
        return objectMapper.convertValue(raw, type);
    }

    /** Read-merge-write: safe for the low-frequency, single-process,
     *  dev-time-only recording workflow this is built for -- not meant for
     *  concurrent writers.
     *  <p>
     *  Merges against the file on disk at {@code sourceDir}, not the
     *  classpath copy {@link #read} uses -- a real bug caught by
     *  {@code FixtureIOTest}: merging against the classpath copy meant every
     *  call after the first overwrote the file with just that one new key,
     *  since the classpath resource never reflects what a previous call in
     *  the same run had just written to disk. */
    public synchronized void record(String filename, String key, Object value) {
        Path path = Path.of(sourceDir + filename);
        Map<String, Object> all = loadFromDisk(path);
        all.put(key, value);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(all));
            log.info("Recorded fixture {} -> {}", filename, key);
        } catch (IOException e) {
            log.warn("Could not write fixture file {}", path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRaw(String filename) {
        ClassPathResource resource = new ClassPathResource("fixtures/" + filename);
        if (!resource.exists()) {
            return new LinkedHashMap<>();
        }
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> parsed = objectMapper.readValue(in, Map.class);
            return new LinkedHashMap<>(parsed);
        } catch (IOException e) {
            log.warn("Could not read fixture file {}", filename, e);
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadFromDisk(Path path) {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(Files.newInputStream(path), Map.class);
            return new LinkedHashMap<>(parsed);
        } catch (IOException e) {
            log.warn("Could not read fixture file {}", path, e);
            return new LinkedHashMap<>();
        }
    }
}
