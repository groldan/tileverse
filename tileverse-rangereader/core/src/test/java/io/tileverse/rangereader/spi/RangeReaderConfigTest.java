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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;

// Attaching a Logback ListAppender from a parallel worker thread can race with SLF4J's
// static binding and return a SubstituteLogger that can't be cast to Logback Logger.
// Run this class single-threaded to avoid that init race.
@Execution(ExecutionMode.SAME_THREAD)
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
        // UUID keeps the key unique across parallel test runs and repeated JVMs,
        // so the static warnedLegacyKeys dedup set is never primed by a prior test.
        String legacy = "io.tileverse.rangereader.test." + UUID.randomUUID();
        RangeReaderConfig.normalizeKey(legacy);
        RangeReaderConfig.normalizeKey(legacy);
        RangeReaderConfig.normalizeKey(legacy);

        long warnings = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(legacy))
                .count();
        assertThat(warnings).isEqualTo(1);
    }

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

        assertThat(out.stringPropertyNames()).allMatch(k -> !k.startsWith("io.tileverse.rangereader."));
        assertThat(out.getProperty("storage.uri")).isEqualTo("file:///tmp/x.pmtiles");
        assertThat(out.getProperty("storage.provider")).isEqualTo("s3");
        assertThat(out.getProperty("storage.s3.region")).isEqualTo("us-west-2");
    }
}
