# RangeReader parameter key prefix removal — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate RangeReaderProvider configuration keys from `io.tileverse.rangereader.<group>.<name>` to `storage.<group>.<name>` while preserving backwards compatibility so existing GeoServer catalog XML continues to work untouched.

**Architecture:** `RangeReaderConfig` is the single normalization boundary: `setParameter`, `getParameter`, and `fromProperties` call a new `normalizeKey` helper that rewrites legacy keys to the canonical `storage.*` form on the fly (emitting one WARN per distinct legacy key per JVM). Every provider's `RangeReaderParameter.key()` constants are updated to the `storage.*` form. The GeoTools bridge (`RangeReaderParams.toProperties`) and the GeoServer EditPanel (`PMTilesDataStoreEditPanel`) each get one small legacy-key-tolerant adjustment.

**Tech Stack:** Java 17+, SLF4J + Logback, JUnit 5, AssertJ, Maven multi-module. Three codebases:
1. `/Users/groldan/git/tileverse-io/tileverse` — branch `remove_prefix_from_rangereader_parameters`
2. `/Users/groldan/git/geotools` — branch `tileverse_upgrade`
3. `/Users/groldan/git/geoserver/geoserver` — branch `tileverse_upgrade`

**Spec:** `docs/superpowers/specs/2026-04-23-rangereader-parameter-key-prefix-removal-design.md`

---

## Phase 1 — Core SPI normalization (tileverse-rangereader)

### Task 1: Write failing tests for RangeReaderConfig key normalization

**Files:**
- Create: `tileverse-rangereader/core/src/test/java/io/tileverse/rangereader/spi/RangeReaderConfigTest.java`

- [ ] **Step 1: Create the test class with failing tests**

```java
/*
 * (c) Copyright 2025 Multiversio LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tileverse.rangereader.spi;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RangeReaderConfigTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger configLogger;

    @BeforeEach
    void setupLogCapture() {
        configLogger = (Logger) LoggerFactory.getLogger(RangeReaderConfig.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        configLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        configLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void normalizeKey_canonicalPrefix_passthrough() {
        assertThat(RangeReaderConfig.normalizeKey("storage.s3.region")).isEqualTo("storage.s3.region");
        assertThat(RangeReaderConfig.normalizeKey("storage.uri")).isEqualTo("storage.uri");
    }

    @Test
    void normalizeKey_legacyPrefix_rewritten() {
        assertThat(RangeReaderConfig.normalizeKey("io.tileverse.rangereader.s3.region"))
                .isEqualTo("storage.s3.region");
        assertThat(RangeReaderConfig.normalizeKey("io.tileverse.rangereader.uri"))
                .isEqualTo("storage.uri");
        assertThat(RangeReaderConfig.normalizeKey("io.tileverse.rangereader.provider"))
                .isEqualTo("storage.provider");
    }

    @Test
    void normalizeKey_unrelated_passthrough() {
        assertThat(RangeReaderConfig.normalizeKey("pmtiles")).isEqualTo("pmtiles");
        assertThat(RangeReaderConfig.normalizeKey("namespace")).isEqualTo("namespace");
    }

    @Test
    void normalizeKey_null_passthrough() {
        assertThat(RangeReaderConfig.normalizeKey(null)).isNull();
    }

    @Test
    void normalizeKeys_mapRewrite_preservesValues() {
        Map<String, Object> in = Map.of(
                "io.tileverse.rangereader.s3.region", "us-west-2",
                "storage.azure.blob-name", "foo.pmtiles",
                "pmtiles", "file:///tmp/x.pmtiles");
        Map<String, Object> out = RangeReaderConfig.normalizeKeys(in);
        assertThat(out).containsOnlyKeys("storage.s3.region", "storage.azure.blob-name", "pmtiles");
        assertThat(out).containsEntry("storage.s3.region", "us-west-2");
        assertThat(out).containsEntry("storage.azure.blob-name", "foo.pmtiles");
        assertThat(out).containsEntry("pmtiles", "file:///tmp/x.pmtiles");
    }

    @Test
    void normalizeKey_legacyTriggersWarnOnce() {
        String legacy = "io.tileverse.rangereader.gcs.project-id-test-only-once";
        RangeReaderConfig.normalizeKey(legacy);
        RangeReaderConfig.normalizeKey(legacy);
        RangeReaderConfig.normalizeKey(legacy);

        long warnings = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(legacy))
                .count();
        assertThat(warnings).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run and verify tests fail**

Run: `./mvnw -pl tileverse-rangereader/core test -Dtest=RangeReaderConfigTest`

Expected: COMPILATION FAILURE — `normalizeKey` and `normalizeKeys` do not exist on `RangeReaderConfig` yet.

- [ ] **Step 3: Commit the failing tests**

```bash
git add tileverse-rangereader/core/src/test/java/io/tileverse/rangereader/spi/RangeReaderConfigTest.java
git commit -m "test: add RangeReaderConfig key normalization tests (failing)"
```

---

### Task 2: Implement normalizeKey / normalizeKeys in RangeReaderConfig

**Files:**
- Modify: `tileverse-rangereader/core/src/main/java/io/tileverse/rangereader/spi/RangeReaderConfig.java`

- [ ] **Step 1: Add imports, logger, and prefix constants**

At the top of the file (existing imports at lines 16-29), add these imports after the `java.util.*` imports:

```java
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Inside the `RangeReaderConfig` class (right after the opening brace at line 36), add:

```java
    private static final Logger log = LoggerFactory.getLogger(RangeReaderConfig.class);

    /**
     * Prefix used on all canonical parameter keys going forward (e.g. {@code storage.s3.region}).
     */
    public static final String KEY_PREFIX = "storage.";

    /**
     * Prefix used by legacy (pre-{@code storage.*}) parameter keys. Legacy keys remain accepted
     * on input for backwards compatibility with existing GeoServer catalogs.
     */
    public static final String LEGACY_KEY_PREFIX = "io.tileverse.rangereader.";

    private static final Set<String> warnedLegacyKeys = ConcurrentHashMap.newKeySet();
```

- [ ] **Step 2: Add normalizeKey and normalizeKeys methods**

Add these as public static methods anywhere in the class (for example, right after the `convertToURI` method around line 344):

```java
    /**
     * Normalizes a parameter key by rewriting the legacy {@value #LEGACY_KEY_PREFIX} prefix to
     * the canonical {@value #KEY_PREFIX} prefix. Logs a one-time WARN per distinct legacy key.
     *
     * @param key The parameter key, possibly {@code null}.
     * @return The normalized key, or {@code key} unchanged if it does not use the legacy prefix.
     */
    public static String normalizeKey(String key) {
        if (key != null && key.startsWith(LEGACY_KEY_PREFIX)) {
            String normalized = KEY_PREFIX + key.substring(LEGACY_KEY_PREFIX.length());
            if (warnedLegacyKeys.add(key)) {
                log.warn(
                        "Deprecated parameter key '{}' — use '{}'. Legacy keys remain accepted but new configurations should use the '{}*' form.",
                        key,
                        normalized,
                        KEY_PREFIX);
            }
            return normalized;
        }
        return key;
    }

    /**
     * Returns a copy of the given map with every key normalized via {@link #normalizeKey(String)}.
     * Iteration order is preserved.
     *
     * @param in source map, must not be {@code null}.
     * @return a new {@link LinkedHashMap} with normalized keys.
     */
    public static Map<String, Object> normalizeKeys(Map<String, ?> in) {
        requireNonNull(in, "in");
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        in.forEach((k, v) -> out.put(normalizeKey(k), v));
        return out;
    }
```

- [ ] **Step 3: Run tests and verify they pass**

Run: `./mvnw -pl tileverse-rangereader/core test -Dtest=RangeReaderConfigTest`

Expected: all 6 tests pass.

- [ ] **Step 4: Commit**

```bash
git add tileverse-rangereader/core/src/main/java/io/tileverse/rangereader/spi/RangeReaderConfig.java
git commit -m "feat(rangereader): add storage.* key prefix and legacy-key normalizer"
```

---

### Task 3: Write failing tests for setParameter/getParameter/fromProperties legacy-key acceptance

**Files:**
- Modify: `tileverse-rangereader/core/src/test/java/io/tileverse/rangereader/spi/RangeReaderConfigTest.java`

- [ ] **Step 1: Append new tests at the end of the class**

Before the final `}` of the class, add:

```java
    @Test
    void setParameter_legacyKey_readableAsCanonical() {
        RangeReaderConfig config = new RangeReaderConfig().uri("file:///tmp/x.pmtiles");
        config.setParameter("io.tileverse.rangereader.s3.region", "eu-west-1");
        assertThat(config.getParameter("storage.s3.region", String.class)).contains("eu-west-1");
    }

    @Test
    void setParameter_canonicalKey_readableAsLegacy() {
        RangeReaderConfig config = new RangeReaderConfig().uri("file:///tmp/x.pmtiles");
        config.setParameter("storage.azure.blob-name", "foo.pmtiles");
        assertThat(config.getParameter("io.tileverse.rangereader.azure.blob-name", String.class))
                .contains("foo.pmtiles");
    }

    @Test
    void setParameter_legacyProviderKey_populatesProviderId() {
        RangeReaderConfig config = new RangeReaderConfig().uri("file:///tmp/x.pmtiles");
        config.setParameter("io.tileverse.rangereader.provider", "s3");
        assertThat(config.providerId()).contains("s3");
    }

    @Test
    void fromProperties_legacyUriAndProviderKeys_parseCorrectly() {
        Properties p = new Properties();
        p.setProperty("io.tileverse.rangereader.uri", "file:///tmp/x.pmtiles");
        p.setProperty("io.tileverse.rangereader.provider", "s3");
        p.setProperty("io.tileverse.rangereader.s3.region", "us-west-2");

        RangeReaderConfig config = RangeReaderConfig.fromProperties(p);

        assertThat(config.uri().toString()).isEqualTo("file:///tmp/x.pmtiles");
        assertThat(config.providerId()).contains("s3");
        assertThat(config.getParameter("storage.s3.region", String.class)).contains("us-west-2");
    }

    @Test
    void fromProperties_canonicalUriAndProviderKeys_parseCorrectly() {
        Properties p = new Properties();
        p.setProperty("storage.uri", "file:///tmp/x.pmtiles");
        p.setProperty("storage.provider", "s3");
        p.setProperty("storage.s3.region", "us-west-2");

        RangeReaderConfig config = RangeReaderConfig.fromProperties(p);

        assertThat(config.uri().toString()).isEqualTo("file:///tmp/x.pmtiles");
        assertThat(config.providerId()).contains("s3");
        assertThat(config.getParameter("storage.s3.region", String.class)).contains("us-west-2");
    }

    @Test
    void toProperties_emitsOnlyCanonicalKeys() {
        RangeReaderConfig config = new RangeReaderConfig().uri("file:///tmp/x.pmtiles");
        config.setParameter("io.tileverse.rangereader.s3.region", "us-west-2");
        config.providerId("s3");

        Properties out = config.toProperties();

        assertThat(out.stringPropertyNames())
                .allMatch(k -> !k.startsWith("io.tileverse.rangereader."));
        assertThat(out.getProperty("storage.uri")).isEqualTo("file:///tmp/x.pmtiles");
        assertThat(out.getProperty("storage.provider")).isEqualTo("s3");
        assertThat(out.getProperty("storage.s3.region")).isEqualTo("us-west-2");
    }
```

- [ ] **Step 2: Run tests — they should fail**

Run: `./mvnw -pl tileverse-rangereader/core test -Dtest=RangeReaderConfigTest`

Expected: previous 6 tests still pass; 6 new tests fail because `setParameter`/`getParameter`/`fromProperties`/constants are not yet wired up.

- [ ] **Step 3: Commit failing tests**

```bash
git add tileverse-rangereader/core/src/test/java/io/tileverse/rangereader/spi/RangeReaderConfigTest.java
git commit -m "test: add legacy-key acceptance tests for RangeReaderConfig (failing)"
```

---

### Task 4: Wire normalization into setParameter/getParameter/fromProperties and flip URI/PROVIDER_ID constants

**Files:**
- Modify: `tileverse-rangereader/core/src/main/java/io/tileverse/rangereader/spi/RangeReaderConfig.java`

- [ ] **Step 1: Flip URI_KEY and PROVIDER_ID_KEY to the canonical form and add legacy constants**

Replace the existing constants block (currently at lines 38-47):

```java
    /**
     * The key used in {@link Properties} to specify the URI of the resource.
     */
    public static final String URI_KEY = "io.tileverse.rangereader.uri";

    /**
     * The key used in {@link Properties} to specify the ID of a {@link RangeReaderProvider}.
     * This can be used to force the use of a specific provider when URI-based disambiguation is not sufficient.
     */
    public static final String PROVIDER_ID_KEY = "io.tileverse.rangereader.provider";
```

with:

```java
    /**
     * The canonical key used in {@link Properties} to specify the URI of the resource.
     */
    public static final String URI_KEY = KEY_PREFIX + "uri";

    /**
     * The canonical key used in {@link Properties} to specify the ID of a {@link RangeReaderProvider}.
     * This can be used to force the use of a specific provider when URI-based disambiguation is not sufficient.
     */
    public static final String PROVIDER_ID_KEY = KEY_PREFIX + "provider";

    /** Legacy URI key, still accepted as input for backwards compatibility. */
    static final String LEGACY_URI_KEY = LEGACY_KEY_PREFIX + "uri";

    /** Legacy provider-id key, still accepted as input for backwards compatibility. */
    static final String LEGACY_PROVIDER_ID_KEY = LEGACY_KEY_PREFIX + "provider";
```

- [ ] **Step 2: Normalize inside setParameter**

Replace the body of `setParameter(String key, Object value)` (currently at lines 148-154):

```java
    public RangeReaderConfig setParameter(String key, Object value) {
        if (FORCE_PROVIDER_ID.key().equals(key)) {
            this.providerId = value == null ? null : String.valueOf(value);
        }
        this.parameterValues.put(requireNonNull(key, "key"), value);
        return this;
    }
```

with:

```java
    public RangeReaderConfig setParameter(String key, Object value) {
        String normalized = normalizeKey(requireNonNull(key, "key"));
        if (FORCE_PROVIDER_ID.key().equals(normalized)) {
            this.providerId = value == null ? null : String.valueOf(value);
        }
        this.parameterValues.put(normalized, value);
        return this;
    }
```

- [ ] **Step 3: Normalize inside getParameter(String, Class)**

Replace the body of `getParameter(String key, Class<T> type)` (currently at lines 205-212):

```java
    public <T> Optional<T> getParameter(String key, Class<T> type) {
        Object value = parameterValues.get(requireNonNull(key, "key"));
        requireNonNull(type, "type");
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(convert(value, type));
    }
```

with:

```java
    public <T> Optional<T> getParameter(String key, Class<T> type) {
        String normalized = normalizeKey(requireNonNull(key, "key"));
        requireNonNull(type, "type");
        Object value = parameterValues.get(normalized);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(convert(value, type));
    }
```

- [ ] **Step 4: Accept legacy URI / provider keys inside fromProperties**

Replace `fromProperties` (currently at lines 276-292):

```java
    public static RangeReaderConfig fromProperties(Properties properties) {
        requireNonNull(properties);
        Object urip = requireNonNull(properties.get(URI_KEY), "Properties must include " + URI_KEY);

        URI uri = convertToURI(urip);
        String providerId = properties.getProperty(FORCE_PROVIDER_ID.key());

        RangeReaderConfig config = new RangeReaderConfig().uri(uri);
        config.providerId(providerId);

        Properties copy = new Properties();
        copy.putAll(properties);
        copy.remove(URI_KEY);
        copy.remove(PROVIDER_ID_KEY);
        copy.forEach((k, v) -> config.setParameter(String.valueOf(k), v));
        return config;
    }
```

with:

```java
    public static RangeReaderConfig fromProperties(Properties properties) {
        requireNonNull(properties);
        Object urip = properties.get(URI_KEY);
        if (urip == null) {
            urip = properties.get(LEGACY_URI_KEY);
        }
        requireNonNull(urip, "Properties must include " + URI_KEY);

        URI uri = convertToURI(urip);
        String providerId = properties.getProperty(PROVIDER_ID_KEY);
        if (providerId == null) {
            providerId = properties.getProperty(LEGACY_PROVIDER_ID_KEY);
        }

        RangeReaderConfig config = new RangeReaderConfig().uri(uri);
        config.providerId(providerId);

        Properties copy = new Properties();
        copy.putAll(properties);
        copy.remove(URI_KEY);
        copy.remove(LEGACY_URI_KEY);
        copy.remove(PROVIDER_ID_KEY);
        copy.remove(LEGACY_PROVIDER_ID_KEY);
        copy.forEach((k, v) -> config.setParameter(String.valueOf(k), v));
        return config;
    }
```

- [ ] **Step 5: Run tests — all should pass**

Run: `./mvnw -pl tileverse-rangereader/core test -Dtest=RangeReaderConfigTest`

Expected: all 12 `RangeReaderConfigTest` tests pass.

- [ ] **Step 6: Run the full core module test suite to catch regressions**

Run: `./mvnw -pl tileverse-rangereader/core test`

Expected: all existing tests pass.

- [ ] **Step 7: Commit**

```bash
git add tileverse-rangereader/core/src/main/java/io/tileverse/rangereader/spi/RangeReaderConfig.java
git commit -m "feat(rangereader): accept legacy keys in RangeReaderConfig, flip URI_KEY/PROVIDER_ID_KEY to storage.*"
```

---

## Phase 2 — Rename all provider parameter keys

### Task 5: Rename CachingProviderHelper keys to storage.caching.*

**Files:**
- Modify: `tileverse-rangereader/core/src/main/java/io/tileverse/rangereader/spi/CachingProviderHelper.java`

- [ ] **Step 1: Replace the three `.key("io.tileverse.rangereader.caching.X")` literals**

At line 38, change:
```java
            .key("io.tileverse.rangereader.caching.enabled")
```
to:
```java
            .key("storage.caching.enabled")
```

At line 61, change:
```java
            .key("io.tileverse.rangereader.caching.blockaligned")
```
to:
```java
            .key("storage.caching.blockaligned")
```

At line 86, change:
```java
            .key("io.tileverse.rangereader.caching.blocksize")
```
to:
```java
            .key("storage.caching.blocksize")
```

- [ ] **Step 2: Run core module tests**

Run: `./mvnw -pl tileverse-rangereader/core test`

Expected: all tests pass (legacy callers still work via `RangeReaderConfig.normalizeKey`).

- [ ] **Step 3: Commit**

```bash
git add tileverse-rangereader/core/src/main/java/io/tileverse/rangereader/spi/CachingProviderHelper.java
git commit -m "refactor(rangereader): rename caching parameter keys to storage.caching.*"
```

---

### Task 6: Rename HttpRangeReaderProvider keys to storage.http.* (including javadoc snippets)

**Files:**
- Modify: `tileverse-rangereader/core/src/main/java/io/tileverse/rangereader/http/HttpRangeReaderProvider.java`

- [ ] **Step 1: Update the eight `.key(...)` literals**

| Line | Old | New |
|---|---|---|
| 120 | `io.tileverse.rangereader.http.timeout-millis` | `storage.http.timeout-millis` |
| 139 | `io.tileverse.rangereader.http.trust-all-certificates` | `storage.http.trust-all-certificates` |
| 159 | `io.tileverse.rangereader.http.username` | `storage.http.username` |
| 178 | `io.tileverse.rangereader.http.password` | `storage.http.password` |
| 198 | `io.tileverse.rangereader.http.bearer-token` | `storage.http.bearer-token` |
| 221 | `io.tileverse.rangereader.http.api-key-headername` | `storage.http.api-key-headername` |
| 242 | `io.tileverse.rangereader.http.api-key` | `storage.http.api-key` |
| 266 | `io.tileverse.rangereader.http.api-key-value-prefix` | `storage.http.api-key-value-prefix` |

- [ ] **Step 2: Update the six Javadoc sample `setProperty` snippets**

At lines 51, 52, 62, 73, 74, change the `"io.tileverse.rangereader.http.X"` strings to `"storage.http.X"`. At line 93 change `"io.tileverse.rangereader.provider"` to `"storage.provider"`.

- [ ] **Step 3: Run core tests**

Run: `./mvnw -pl tileverse-rangereader/core test`

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add tileverse-rangereader/core/src/main/java/io/tileverse/rangereader/http/HttpRangeReaderProvider.java
git commit -m "refactor(rangereader): rename HTTP parameter keys to storage.http.*"
```

---

### Task 7: Rename S3RangeReaderProvider keys to storage.s3.*

**Files:**
- Modify: `tileverse-rangereader/s3/src/main/java/io/tileverse/rangereader/s3/S3RangeReaderProvider.java`

- [ ] **Step 1: Update the six `.key(...)` literals**

| Line | Old | New |
|---|---|---|
| 97 | `io.tileverse.rangereader.s3.force-path-style` | `storage.s3.force-path-style` |
| 120 | `io.tileverse.rangereader.s3.region` | `storage.s3.region` |
| 153 | `io.tileverse.rangereader.s3.aws-access-key-id` | `storage.s3.aws-access-key-id` |
| 174 | `io.tileverse.rangereader.s3.aws-secret-access-key` | `storage.s3.aws-secret-access-key` |
| 195 | `io.tileverse.rangereader.s3.use-default-credentials-provider` | `storage.s3.use-default-credentials-provider` |
| 218 | `io.tileverse.rangereader.s3.default-credentials-profile` | `storage.s3.default-credentials-profile` |

- [ ] **Step 2: Search for any `"io.tileverse.rangereader.s3."` strings in javadoc/comments of this file**

Run: `grep -n "io\.tileverse\.rangereader\.s3\." tileverse-rangereader/s3/src/main/java/io/tileverse/rangereader/s3/S3RangeReaderProvider.java`

Replace any remaining occurrences to `storage.s3.` (same occurrences as step 1 should now return zero or only comments).

- [ ] **Step 3: Run the S3 module tests**

Run: `./mvnw -pl tileverse-rangereader/s3 test -DskipITs`

Expected: all unit tests pass.

- [ ] **Step 4: Commit**

```bash
git add tileverse-rangereader/s3/src/main/java/io/tileverse/rangereader/s3/S3RangeReaderProvider.java
git commit -m "refactor(rangereader): rename S3 parameter keys to storage.s3.*"
```

---

### Task 8: Rename AzureBlobRangeReaderProvider keys to storage.azure.*

**Files:**
- Modify: `tileverse-rangereader/azure/src/main/java/io/tileverse/rangereader/azure/AzureBlobRangeReaderProvider.java`

- [ ] **Step 1: Update the three `.key(...)` literals**

| Line | Old | New |
|---|---|---|
| 85 | `io.tileverse.rangereader.azure.blob-name` | `storage.azure.blob-name` |
| 107 | `io.tileverse.rangereader.azure.account-key` | `storage.azure.account-key` |
| 127 | `io.tileverse.rangereader.azure.sas-token` | `storage.azure.sas-token` |

- [ ] **Step 2: Run azure module tests**

Run: `./mvnw -pl tileverse-rangereader/azure test -DskipITs`

Expected: all unit tests pass.

- [ ] **Step 3: Commit**

```bash
git add tileverse-rangereader/azure/src/main/java/io/tileverse/rangereader/azure/AzureBlobRangeReaderProvider.java
git commit -m "refactor(rangereader): rename Azure parameter keys to storage.azure.*"
```

---

### Task 9: Rename GoogleCloudStorageRangeReaderProvider keys to storage.gcs.*

**Files:**
- Modify: `tileverse-rangereader/gcs/src/main/java/io/tileverse/rangereader/gcs/GoogleCloudStorageRangeReaderProvider.java`

- [ ] **Step 1: Update the three `.key(...)` literals**

| Line | Old | New |
|---|---|---|
| 86 | `io.tileverse.rangereader.gcs.project-id` | `storage.gcs.project-id` |
| 111 | `io.tileverse.rangereader.gcs.quota-project-id` | `storage.gcs.quota-project-id` |
| 128 | `io.tileverse.rangereader.gcs.default-credentials-chain` | `storage.gcs.default-credentials-chain` |

- [ ] **Step 2: Run gcs module tests**

Run: `./mvnw -pl tileverse-rangereader/gcs test -DskipITs`

Expected: all unit tests pass.

- [ ] **Step 3: Commit**

```bash
git add tileverse-rangereader/gcs/src/main/java/io/tileverse/rangereader/gcs/GoogleCloudStorageRangeReaderProvider.java
git commit -m "refactor(rangereader): rename GCS parameter keys to storage.gcs.*"
```

---

## Phase 3 — Cross-module SPI guard test

### Task 10: Add SPI-wide guard test in the aggregator module

**Files:**
- Create: `tileverse-rangereader/all/src/test/java/io/tileverse/rangereader/spi/SpiKeyConventionTest.java`

- [ ] **Step 1: Write the guard test**

```java
/*
 * (c) Copyright 2025 Multiversio LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tileverse.rangereader.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guard test: every {@link RangeReaderParameter} declared by any {@link RangeReaderProvider}
 * on the classpath must use the canonical {@value RangeReaderConfig#KEY_PREFIX} prefix and must
 * NOT use the legacy {@value RangeReaderConfig#LEGACY_KEY_PREFIX} prefix.
 */
class SpiKeyConventionTest {

    @Test
    void allProviderParameterKeysUseStoragePrefix() {
        List<RangeReaderProvider> providers = RangeReaderProvider.getProviders();
        assertThat(providers).as("expected SPI providers to be discovered").isNotEmpty();

        for (RangeReaderProvider provider : providers) {
            for (RangeReaderParameter<?> p : provider.getParameters()) {
                assertThat(p.key())
                        .as("%s parameter key must start with %s", provider.getId(), RangeReaderConfig.KEY_PREFIX)
                        .startsWith(RangeReaderConfig.KEY_PREFIX);
                assertThat(p.key())
                        .as("%s parameter key must not use the legacy %s prefix", provider.getId(), RangeReaderConfig.LEGACY_KEY_PREFIX)
                        .doesNotStartWith(RangeReaderConfig.LEGACY_KEY_PREFIX);
            }
        }
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./mvnw -pl tileverse-rangereader/all test -Dtest=SpiKeyConventionTest -DskipITs`

Expected: test passes. If it fails, a provider was missed in Phase 2 — fix it before proceeding.

- [ ] **Step 3: Run the whole rangereader reactor to verify no module regresses**

Run: `./mvnw -pl tileverse-rangereader/core,tileverse-rangereader/s3,tileverse-rangereader/azure,tileverse-rangereader/gcs,tileverse-rangereader/all -am test -DskipITs`

Expected: all unit tests pass across all modules.

- [ ] **Step 4: Commit**

```bash
git add tileverse-rangereader/all/src/test/java/io/tileverse/rangereader/spi/SpiKeyConventionTest.java
git commit -m "test(rangereader): add SPI-wide guard for storage.* key convention"
```

---

### Task 11: Install tileverse snapshot locally so geotools can consume it

**Files:** none — one command only.

- [ ] **Step 1: Install the tileverse reactor to the local Maven repo**

From the tileverse root (`/Users/groldan/git/tileverse-io/tileverse`):

Run: `./mvnw -pl tileverse-rangereader/core,tileverse-rangereader/s3,tileverse-rangereader/azure,tileverse-rangereader/gcs,tileverse-rangereader/all,bom,dependencies -am install -DskipTests`

Expected: `BUILD SUCCESS`. Installs the current `${revision}` (e.g. `1.1-SNAPSHOT`) to `~/.m2/repository/io/tileverse/rangereader/`.

- [ ] **Step 2: Confirm the snapshot JAR exists**

Run: `ls ~/.m2/repository/io/tileverse/rangereader/tileverse-rangereader-core/*-SNAPSHOT/*.jar`

Expected: one or more JAR files listed.

No commit for this step (build artifact only).

---

## Phase 4 — GeoTools pmtiles bridge

> All Phase 4 work happens in `/Users/groldan/git/geotools` on branch `tileverse_upgrade`.

### Task 12: Write failing bridge-level unit test for legacy-key input

**Files:**
- Create: `/Users/groldan/git/geotools/modules/unsupported/pmtiles/src/test/java/org/geotools/tileverse/rangereader/RangeReaderParamsTest.java` (if it does not already exist — run `ls` first and modify instead of create if it exists)

- [ ] **Step 1: Confirm branch and sync tileverse dependency version**

Run:
```bash
cd /Users/groldan/git/geotools
git status
git log --oneline -3
```

Confirm you are on `tileverse_upgrade`. Check the pmtiles module's tileverse dependency version:

Run: `grep -rn "tileverse-rangereader" /Users/groldan/git/geotools/modules/unsupported/pmtiles/pom.xml`

It must resolve to the version installed in Task 11. If the POM pins a specific older version, update it to the installed SNAPSHOT.

- [ ] **Step 2: Write the failing unit test**

```java
/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2025, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 */
package org.geotools.tileverse.rangereader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;

/**
 * Verifies that {@link RangeReaderParams#toProperties(Map)} accepts pre-{@code storage.*} legacy
 * parameter keys emitted by existing GeoServer catalogs.
 */
public class RangeReaderParamsTest {

    @Test
    public void toPropertiesNormalizesLegacyKeys() {
        Map<String, Object> connectionParams = new HashMap<>();
        connectionParams.put("pmtiles", "file:///tmp/sample.pmtiles");
        connectionParams.put("io.tileverse.rangereader.provider", "file");
        connectionParams.put("io.tileverse.rangereader.caching.enabled", Boolean.TRUE);
        connectionParams.put("io.tileverse.rangereader.s3.region", "us-west-2");

        Properties props = RangeReaderParams.toProperties(connectionParams);

        assertEquals("file", props.getProperty("storage.provider"));
        assertEquals("true", props.getProperty("storage.caching.enabled"));
        assertEquals("us-west-2", props.getProperty("storage.s3.region"));
        assertFalse(
                "no legacy keys should remain in the output",
                props.stringPropertyNames().stream().anyMatch(k -> k.startsWith("io.tileverse.rangereader.")));
    }

    @Test
    public void toPropertiesPassesCanonicalKeys() {
        Map<String, Object> connectionParams = new HashMap<>();
        connectionParams.put("pmtiles", "file:///tmp/sample.pmtiles");
        connectionParams.put("storage.provider", "file");
        connectionParams.put("storage.caching.enabled", Boolean.TRUE);

        Properties props = RangeReaderParams.toProperties(connectionParams);

        assertEquals("file", props.getProperty("storage.provider"));
        assertEquals("true", props.getProperty("storage.caching.enabled"));
    }
}
```

Note: the GeoTools codebase mixes JUnit 4 and JUnit 5. If the pmtiles module uses JUnit 5, swap imports to `org.junit.jupiter.api.Test` and `static org.junit.jupiter.api.Assertions.*` — check one existing test in the module to confirm style.

- [ ] **Step 3: Run the failing test**

Run:
```bash
cd /Users/groldan/git/geotools
mvn -pl modules/unsupported/pmtiles test -Dtest=RangeReaderParamsTest
```

Expected: `toPropertiesNormalizesLegacyKeys` FAILS because `param.lookUp(map)` fails to find values under legacy keys when `param.key` has already been flipped to `storage.*`. `toPropertiesPassesCanonicalKeys` should PASS.

- [ ] **Step 4: Commit the failing test**

```bash
cd /Users/groldan/git/geotools
git add modules/unsupported/pmtiles/src/test/java/org/geotools/tileverse/rangereader/RangeReaderParamsTest.java
git commit -m "test(pmtiles): add RangeReaderParams legacy-key normalization test (failing)"
```

---

### Task 13: Update RangeReaderParams.toProperties to normalize incoming map keys

**Files:**
- Modify: `/Users/groldan/git/geotools/modules/unsupported/pmtiles/src/main/java/org/geotools/tileverse/rangereader/RangeReaderParams.java`

- [ ] **Step 1: Replace the toProperties method**

Replace the existing `toProperties` (currently at lines 232-237):

```java
    public static Properties toProperties(Map<String, ?> connectionParams) {
        Properties configOpts = new Properties();
        addProperty(RANGEREADER_PROVIDER_ID, connectionParams, configOpts);
        PROVIDER_PARAMS.forEach(param -> addProperty(param, connectionParams, configOpts));
        return configOpts;
    }
```

with:

```java
    public static Properties toProperties(Map<String, ?> connectionParams) {
        Map<String, Object> normalized = RangeReaderConfig.normalizeKeys(connectionParams);
        Properties configOpts = new Properties();
        addProperty(RANGEREADER_PROVIDER_ID, normalized, configOpts);
        PROVIDER_PARAMS.forEach(param -> addProperty(param, normalized, configOpts));
        return configOpts;
    }
```

- [ ] **Step 2: Add the missing import at the top of the file**

Inside the imports block (currently ending around line 39), add:
```java
import io.tileverse.rangereader.spi.RangeReaderConfig;
```
(Only if the import does not already exist — the file already imports `RangeReaderConfig`, so likely no change is needed. If so, skip this step.)

- [ ] **Step 3: Run the failing test — it should now pass**

Run:
```bash
cd /Users/groldan/git/geotools
mvn -pl modules/unsupported/pmtiles test -Dtest=RangeReaderParamsTest
```

Expected: both tests pass.

- [ ] **Step 4: Run the full pmtiles test class**

Run:
```bash
cd /Users/groldan/git/geotools
mvn -pl modules/unsupported/pmtiles test
```

Expected: all pmtiles tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/groldan/git/geotools
git add modules/unsupported/pmtiles/src/main/java/org/geotools/tileverse/rangereader/RangeReaderParams.java
git commit -m "fix(pmtiles): normalize legacy range-reader keys in RangeReaderParams.toProperties"
```

---

### Task 14: Install updated geotools snapshot for geoserver consumption

**Files:** none — one command only.

- [ ] **Step 1: Install the pmtiles module (and its dependencies) locally**

Run:
```bash
cd /Users/groldan/git/geotools
mvn -pl modules/unsupported/pmtiles -am install -DskipTests
```

Expected: `BUILD SUCCESS`.

No commit.

---

## Phase 5 — GeoServer pmtiles-store community plugin

> All Phase 5 work happens in `/Users/groldan/git/geoserver/geoserver` on branch `tileverse_upgrade`.

### Task 15: Write failing factory test for legacy catalog connectionParameters

**Files:**
- Create or modify: find the existing factory-level test in `src/community/pmtiles-store/src/test/java/org/geoserver/pmtiles/` (locate with `find`). Most likely `PMTilesDataStoreFactoryTest.java` or an equivalent.

- [ ] **Step 1: Confirm branch and tileverse-geotools version**

Run:
```bash
cd /Users/groldan/git/geoserver/geoserver
git status
git log --oneline -3
```

Confirm branch is `tileverse_upgrade` and the pmtiles-store plugin's POM references the GeoTools version matching the snapshot installed in Task 14.

- [ ] **Step 2: Locate or create a factory-level test**

Run:
```bash
find /Users/groldan/git/geoserver/geoserver/src/community/pmtiles-store/src/test -name "*Test*.java"
```

- If a `PMTilesDataStoreFactoryTest` (or any test that exercises `createDataStore(Map)`) exists, add a new `@Test` method to it.
- If none exists, create one. Use the existing community-plugin test style (Spring test runner, GeoServerSystemTestSupport, or plain JUnit — match the package's convention).

- [ ] **Step 3: Add a legacy-keys test**

Example body (adjust imports and superclass to match existing style):

```java
    @Test
    public void testLegacyConnectionParametersStillWork() throws Exception {
        Map<String, Serializable> connectionParams = new HashMap<>();
        connectionParams.put("pmtiles", getSamplePMTilesUri());
        connectionParams.put("io.tileverse.rangereader.provider", "file");
        connectionParams.put("io.tileverse.rangereader.caching.enabled", Boolean.TRUE);

        PMTilesDataStoreFactory factory = new PMTilesDataStoreFactory();
        DataStore store = factory.createDataStore(new HashMap<>(connectionParams));
        try {
            assertNotNull(store);
            assertFalse(store.getNames().isEmpty());
        } finally {
            store.dispose();
        }
    }
```

- [ ] **Step 4: Run the test — confirm it passes without further code change**

Run:
```bash
cd /Users/groldan/git/geoserver/geoserver
mvn -pl src/community/pmtiles-store test -Dtest=<the test class>#testLegacyConnectionParametersStillWork
```

Expected: test passes on first run — the `PMTilesDataStoreFactory.createDataStore` path already runs through the bridge fix from Task 13, so legacy keys are already accepted end-to-end.

If the test FAILS: the bridge fix from Task 13 is not being reached. Verify the installed GeoTools snapshot version matches the plugin's dependency declaration.

- [ ] **Step 5: Commit**

```bash
cd /Users/groldan/git/geoserver/geoserver
git add src/community/pmtiles-store/src/test
git commit -m "test(pmtiles-store): add legacy connectionParameters compatibility test"
```

---

### Task 16: Update PMTilesDataStoreEditPanel visibility checks to storage.* prefixes

**Files:**
- Modify: `/Users/groldan/git/geoserver/geoserver/src/community/pmtiles-store/src/main/java/org/geoserver/pmtiles/web/data/PMTilesDataStoreEditPanel.java`

- [ ] **Step 1: Replace the `alwaysVisible` set and all prefix checks**

Replace the body of `applyVisibility(String paramName, Panel paramPanel, RangeReaderChangedEvent event)` (currently at lines 132-159):

```java
    private void applyVisibility(String paramName, Panel paramPanel, RangeReaderChangedEvent event) {
        final String providerId = event.providerId() == null ? "" : event.providerId();
        final Set<String> alwaysVisible = Set.of("namespace", "pmtiles", "io.tileverse.rangereader.provider");
        final Set<String> cacheable = Set.of("http", "s3", "gcs", "azure");

        if (alwaysVisible.contains(paramName)) {
            return;
        }
        boolean visible = false;
        if (paramName.startsWith("io.tileverse.rangereader.caching")) {
            visible = cacheable.contains(providerId);
        } else if ("s3".equals(providerId)) {
            visible = paramName.startsWith("io.tileverse.rangereader.s3.");
        } else if ("azure".equals(providerId)) {
            visible = paramName.startsWith("io.tileverse.rangereader.azure.");
        } else if ("gcs".equals(providerId)) {
            visible = paramName.startsWith("io.tileverse.rangereader.gcs.");
        } else if ("http".equals(providerId)) {
            visible = paramName.startsWith("io.tileverse.rangereader.http.");
        } else if ("file".equals(providerId)) {
            visible = paramName.startsWith("io.tileverse.rangereader.file.");
        }

        paramPanel.setVisible(visible);
        if (event.target() != null) {
            event.target().add(paramPanel);
        }
    }
```

with:

```java
    private void applyVisibility(String paramName, Panel paramPanel, RangeReaderChangedEvent event) {
        final String providerId = event.providerId() == null ? "" : event.providerId();
        final Set<String> alwaysVisible = Set.of("namespace", "pmtiles", "storage.provider");
        final Set<String> cacheable = Set.of("http", "s3", "gcs", "azure");

        if (alwaysVisible.contains(paramName)) {
            return;
        }
        boolean visible = false;
        if (paramName.startsWith("storage.caching.")) {
            visible = cacheable.contains(providerId);
        } else if ("s3".equals(providerId)) {
            visible = paramName.startsWith("storage.s3.");
        } else if ("azure".equals(providerId)) {
            visible = paramName.startsWith("storage.azure.");
        } else if ("gcs".equals(providerId)) {
            visible = paramName.startsWith("storage.gcs.");
        } else if ("http".equals(providerId)) {
            visible = paramName.startsWith("storage.http.");
        } else if ("file".equals(providerId)) {
            visible = paramName.startsWith("storage.file.");
        }

        paramPanel.setVisible(visible);
        if (event.target() != null) {
            event.target().add(paramPanel);
        }
    }
```

- [ ] **Step 2: Compile the plugin**

Run:
```bash
cd /Users/groldan/git/geoserver/geoserver
mvn -pl src/community/pmtiles-store compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
cd /Users/groldan/git/geoserver/geoserver
git add src/community/pmtiles-store/src/main/java/org/geoserver/pmtiles/web/data/PMTilesDataStoreEditPanel.java
git commit -m "refactor(pmtiles-store): update visibility prefix checks to storage.*"
```

---

### Task 17: Rewrite PMTilesDataStoreEditPanel.onBeforeRender to normalize legacy keys in-memory

**Files:**
- Modify: `/Users/groldan/git/geoserver/geoserver/src/community/pmtiles-store/src/main/java/org/geoserver/pmtiles/web/data/PMTilesDataStoreEditPanel.java`

- [ ] **Step 1: Replace the onBeforeRender method**

Replace the method at lines 73-79:

```java
    @Override
    protected void onBeforeRender() {
        super.onBeforeRender();
        DataStoreInfo storeInfo = (DataStoreInfo) super.storeEditForm.getModelObject();
        String providerId = (String) storeInfo.getConnectionParameters().get(RANGEREADER_PROVIDER_ID.key);
        sendEvent(new RangeReaderChangedEvent(providerId, null));
    }
```

with:

```java
    @Override
    protected void onBeforeRender() {
        DataStoreInfo storeInfo = (DataStoreInfo) super.storeEditForm.getModelObject();
        Map<String, Serializable> params = storeInfo.getConnectionParameters();
        // Rewrite any legacy (io.tileverse.rangereader.*) keys into the canonical storage.* form
        // in place so Wicket MapModel widgets (keyed by the factory's short Param.key) see values.
        Map<String, Serializable> rewritten = new LinkedHashMap<>(params.size());
        params.forEach((k, v) -> rewritten.put(RangeReaderConfig.normalizeKey(k), v));
        params.clear();
        params.putAll(rewritten);

        super.onBeforeRender();
        String providerId = (String) params.get(RANGEREADER_PROVIDER_ID.key);
        sendEvent(new RangeReaderChangedEvent(providerId, null));
    }
```

- [ ] **Step 2: Add the missing imports**

At the top of the file, add (inside the existing imports block):
```java
import io.tileverse.rangereader.spi.RangeReaderConfig;
import java.util.LinkedHashMap;
```

- [ ] **Step 3: Compile**

Run:
```bash
cd /Users/groldan/git/geoserver/geoserver
mvn -pl src/community/pmtiles-store compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
cd /Users/groldan/git/geoserver/geoserver
git add src/community/pmtiles-store/src/main/java/org/geoserver/pmtiles/web/data/PMTilesDataStoreEditPanel.java
git commit -m "fix(pmtiles-store): normalize legacy connectionParameters keys at edit time"
```

---

### Task 18: Update localization resource keys

**Files:**
- Find via: `find /Users/groldan/git/geoserver/geoserver/src/community/pmtiles-store -name "*.properties"`

Typical candidate: `PMTilesDataStoreEditPanel.properties` (bundled beside the panel) or a module-level `GeoServerApplication.properties`.

- [ ] **Step 1: Locate existing resource entries that use the old key**

Run:
```bash
cd /Users/groldan/git/geoserver/geoserver
grep -rn "io\.tileverse\.rangereader" src/community/pmtiles-store/src/main/resources
```

- [ ] **Step 2: For each matching entry, add a copy of the entry with the `storage.*` prefix as the key, keeping the legacy-keyed entry as an alias**

Example: if the file contains

```
io.tileverse.rangereader.provider=Provider
io.tileverse.rangereader.provider.s3=Amazon S3
```

add, directly below each:

```
storage.provider=Provider
storage.provider.s3=Amazon S3
```

Leave the old entries in place so any code path still resolving via the legacy key keeps working during the transition.

- [ ] **Step 3: Build the plugin**

Run:
```bash
cd /Users/groldan/git/geoserver/geoserver
mvn -pl src/community/pmtiles-store package -DskipTests
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
cd /Users/groldan/git/geoserver/geoserver
git add src/community/pmtiles-store/src/main/resources
git commit -m "i18n(pmtiles-store): add storage.* resource keys alongside legacy ones"
```

---

### Task 19: Add Wicket EditPanel test covering legacy DataStoreInfo

**Files:**
- Find / create an edit-panel test under `src/community/pmtiles-store/src/test/java/org/geoserver/pmtiles/web/`.

- [ ] **Step 1: Discover existing Wicket test infrastructure**

Run:
```bash
find /Users/groldan/git/geoserver/geoserver/src/community/pmtiles-store/src/test -name "*EditPanel*Test*.java"
grep -rln "GeoServerWicketTestSupport\|WicketTester" /Users/groldan/git/geoserver/geoserver/src/community/pmtiles-store/src/test
```

If no existing edit-panel test exists, find the nearest equivalent in another community plugin (e.g. a WMTS/WMS store edit panel test) to match base class and harness style.

- [ ] **Step 2: Add the test**

Minimal test shape (adjust base class to match project convention):

```java
    @Test
    public void testEditPanelNormalizesLegacyConnectionParameters() {
        Catalog catalog = getCatalog();
        DataStoreInfo store = catalog.getFactory().createDataStore();
        store.setName("legacy-pmtiles");
        store.setType("PMTiles");
        Map<String, Serializable> params = store.getConnectionParameters();
        params.put("pmtiles", getSamplePMTilesUri().toString());
        params.put("io.tileverse.rangereader.provider", "file");
        params.put("io.tileverse.rangereader.caching.enabled", Boolean.TRUE);

        tester.startComponentInPage(new PMTilesDataStoreEditPanel("panel", newMockForm(store)));

        // After render, onBeforeRender should have rewritten legacy keys.
        assertThat(store.getConnectionParameters()).containsKey("storage.provider");
        assertThat(store.getConnectionParameters()).containsKey("storage.caching.enabled");
        assertThat(store.getConnectionParameters()).doesNotContainKey("io.tileverse.rangereader.provider");
    }
```

`newMockForm(storeInfo)` is a test helper — reuse whatever helper exists in the community plugin's test support. If none exists, adapt the minimal Wicket form construction from an existing community plugin edit-panel test.

- [ ] **Step 3: Run the test**

Run:
```bash
cd /Users/groldan/git/geoserver/geoserver
mvn -pl src/community/pmtiles-store test -Dtest=<new test class>
```

Expected: the test passes.

- [ ] **Step 4: Run the full pmtiles-store test suite**

Run:
```bash
cd /Users/groldan/git/geoserver/geoserver
mvn -pl src/community/pmtiles-store test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/groldan/git/geoserver/geoserver
git add src/community/pmtiles-store/src/test
git commit -m "test(pmtiles-store): verify EditPanel normalizes legacy keys on render"
```

---

## Phase 6 — Verification

### Task 20: End-to-end sanity checks

- [ ] **Step 1: Rebuild the tileverse reactor from scratch**

```bash
cd /Users/groldan/git/tileverse-io/tileverse
make verify
```

Expected: `make verify` passes (lint + unit tests + integration tests).

- [ ] **Step 2: Rebuild the geotools pmtiles module**

```bash
cd /Users/groldan/git/geotools
mvn -pl modules/unsupported/pmtiles test
```

Expected: all tests pass.

- [ ] **Step 3: Rebuild the geoserver pmtiles-store plugin**

```bash
cd /Users/groldan/git/geoserver/geoserver
mvn -pl src/community/pmtiles-store test
```

Expected: all tests pass.

- [ ] **Step 4: Search all three codebases for any remaining literal legacy prefix outside javadoc/comments**

Run:
```bash
grep -rn '"io\.tileverse\.rangereader\.' /Users/groldan/git/tileverse-io/tileverse/tileverse-rangereader/*/src/main/java/
grep -rn '"io\.tileverse\.rangereader\.' /Users/groldan/git/geotools/modules/unsupported/pmtiles/src/main/java/
grep -rn '"io\.tileverse\.rangereader\.' /Users/groldan/git/geoserver/geoserver/src/community/pmtiles-store/src/main/java/
```

Expected results:
- In `tileverse/.../spi/RangeReaderConfig.java`: only `LEGACY_KEY_PREFIX = "io.tileverse.rangereader."` and legacy-key constants. No other occurrences.
- In `tileverse/.../HttpRangeReaderProvider.java` and similar: javadoc `{@code ...}` blocks may still mention the legacy form as historical notes — that's OK. No `.key(...)` literal should match.
- In geotools/geoserver main source: zero occurrences.

Any unexpected hit → open the file and convert it. Commit the fix as `chore: drop stray legacy key reference`.

- [ ] **Step 5: Summarize commit history for each branch**

```bash
cd /Users/groldan/git/tileverse-io/tileverse && git log --oneline remove_prefix_from_rangereader_parameters ^main | head -30
cd /Users/groldan/git/geotools && git log --oneline tileverse_upgrade ^main | head -30
cd /Users/groldan/git/geoserver/geoserver && git log --oneline tileverse_upgrade ^main | head -30
```

Confirm each branch has the expected commits and a clean linear history. No final commit — this step is reporting only.
