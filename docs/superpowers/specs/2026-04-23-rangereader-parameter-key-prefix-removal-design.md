# RangeReader parameter key prefix removal (backwards compatible)

- **Date:** 2026-04-23
- **Status:** Design approved, ready for implementation plan
- **Scope:** `tileverse-rangereader` (this repo) + `geotools/modules/unsupported/pmtiles` (branch `tileverse_upgrade`) + `geoserver/src/community/pmtiles-store` (branch `tileverse_upgrade`)

## Goal

Rename every `RangeReaderParameter` key from `io.tileverse.rangereader.<group>.<name>` to `storage.<group>.<name>` (e.g. `io.tileverse.rangereader.azure.blob-name` → `storage.azure.blob-name`), while keeping existing GeoServer catalog XML that stores the long keys fully functional without any migration step.

The `storage.` namespace is deliberately generic (not `tileverse.` or `rangereader.`) to make room for an upcoming broader blob-storage refactor that is not limited to range readers. It keeps collision risk with unrelated GeoTools DataStore factories near zero without forcing us to introduce a discriminator parameter (à la JDBC's `dbtype`) — the presence of a `storage.uri` key is itself enough to identify our factory's intent.

## Decisions

| Decision | Value |
|---|---|
| Canonical key form | `storage.<group>.<name>` (top-level: `storage.uri`, `storage.provider`) |
| Legacy long keys | Remain accepted forever on read; never emitted by new code; never rewritten in catalog XML |
| Normalization location | Inside `RangeReaderConfig` only (single source of truth) |
| Bridge layers | Pass maps through; GeoTools bridge calls `RangeReaderConfig.normalizeKeys(Map)` once before `Param.lookUp` |
| Deprecation signal | `log.warn` once per distinct legacy key per JVM |
| Catalog migration | None — pure read-side backcompat; opt-in migration on next form save |

## Examples of the key change

| Before | After |
|---|---|
| `io.tileverse.rangereader.uri` | `storage.uri` |
| `io.tileverse.rangereader.provider` | `storage.provider` |
| `io.tileverse.rangereader.caching.enabled` | `storage.caching.enabled` |
| `io.tileverse.rangereader.caching.blockaligned` | `storage.caching.blockaligned` |
| `io.tileverse.rangereader.caching.blocksize` | `storage.caching.blocksize` |
| `io.tileverse.rangereader.http.connection-timeout-millis` | `storage.http.connection-timeout-millis` |
| `io.tileverse.rangereader.s3.region` | `storage.s3.region` |
| `io.tileverse.rangereader.azure.blob-name` | `storage.azure.blob-name` |
| `io.tileverse.rangereader.gcs.project-id` | `storage.gcs.project-id` |

The `storage.` + `<group>` double namespace keeps every key globally unique across GeoTools factories and within our own future expansions.

## Design

### 1. `RangeReaderConfig` as the normalization boundary

Add prefix constants and a helper (promoted to public API so the GeoTools bridge and any other consumer can reuse it):

```java
/** Prefix used on all canonical parameter keys going forward. */
public static final String KEY_PREFIX = "storage.";

/** Prefix used on legacy (pre-storage) parameter keys; still accepted on input. */
public static final String LEGACY_KEY_PREFIX = "io.tileverse.rangereader.";

public static String normalizeKey(String key) {
    if (key != null && key.startsWith(LEGACY_KEY_PREFIX)) {
        String normalized = KEY_PREFIX + key.substring(LEGACY_KEY_PREFIX.length());
        if (warnedLegacyKeys.add(key)) {
            log.warn(
                "Deprecated parameter key '{}' — use '{}'. Legacy keys remain accepted but new configurations should use the '{}*' form.",
                key, normalized, KEY_PREFIX);
        }
        return normalized;
    }
    return key;
}

public static Map<String, Object> normalizeKeys(Map<String, ?> in) {
    Map<String, Object> out = new LinkedHashMap<>(in.size());
    in.forEach((k, v) -> out.put(normalizeKey(k), v));
    return out;
}

private static final Set<String> warnedLegacyKeys = ConcurrentHashMap.newKeySet();
```

Apply `normalizeKey` inside:
- `setParameter(String key, Object value)` — normalize before storing; `FORCE_PROVIDER_ID.key()` comparison uses the normalized form.
- `getParameter(String key, Class<T> type)` — normalize before map lookup.
- `fromProperties(Properties)` — accept either canonical or legacy form of `URI_KEY` / `PROVIDER_ID_KEY`; delegate all other entries to `setParameter`, which normalizes.

Constants:

```java
public static final String URI_KEY = KEY_PREFIX + "uri";             // "storage.uri"
public static final String PROVIDER_ID_KEY = KEY_PREFIX + "provider"; // "storage.provider"
static final String LEGACY_URI_KEY = LEGACY_KEY_PREFIX + "uri";
static final String LEGACY_PROVIDER_ID_KEY = LEGACY_KEY_PREFIX + "provider";
```

`toProperties()` writes only canonical `storage.*` keys — the internal map already stores normalized keys, so no change is needed beyond the constant flip.

### 2. Parameter constant rename across all providers

Mechanical edit: every `.key("io.tileverse.rangereader.X")` becomes `.key("storage.X")`. Files:

- `tileverse-rangereader/core/.../spi/CachingProviderHelper.java` — `storage.caching.enabled`, `storage.caching.blockaligned`, `storage.caching.blocksize`
- `tileverse-rangereader/core/.../http/HttpRangeReaderProvider.java` — `storage.http.*`
- `tileverse-rangereader/core/.../file/FileRangeReaderProvider.java` — `storage.file.*`
- `tileverse-rangereader/s3/.../S3RangeReaderProvider.java` — `storage.s3.*`
- `tileverse-rangereader/azure/.../AzureBlobRangeReaderProvider.java` — `storage.azure.*`
- `tileverse-rangereader/gcs/.../GoogleCloudStorageRangeReaderProvider.java` — `storage.gcs.*`

`RangeReaderConfig.FORCE_PROVIDER_ID` already references `PROVIDER_ID_KEY` via the constant, so its key flips automatically.

### 3. GeoTools bridge fix — `RangeReaderParams.toProperties`

Current code does `param.lookUp(connectionParams)`, where `param.key` is now the canonical `storage.*` form but a legacy catalog may supply long keys. Fix: normalize the input map once up front.

```java
public static Properties toProperties(Map<String, ?> connectionParams) {
    Map<String, Object> normalized = RangeReaderConfig.normalizeKeys(connectionParams);
    Properties configOpts = new Properties();
    addProperty(RANGEREADER_PROVIDER_ID, normalized, configOpts);
    PROVIDER_PARAMS.forEach(param -> addProperty(param, normalized, configOpts));
    return configOpts;
}
```

No other changes to `RangeReaderParams` — `dataStoreParam(RangeReaderParameter)` pulls `param.key()`, which is now the canonical `storage.*` form, so the GeoTools `Param` objects presented to GeoServer automatically carry `storage.*` keys.

### 4. GeoServer EditPanel — `PMTilesDataStoreEditPanel`

Two updates are needed here:

**4a. Prefix-based visibility checks** (`applyVisibility`, ~lines 134-153): move from legacy prefixes to `storage.*`.

```java
final Set<String> alwaysVisible = Set.of("namespace", "pmtiles", "storage.provider");

if (paramName.startsWith("storage.caching.")) { ... }
else if ("s3".equals(providerId))    visible = paramName.startsWith("storage.s3.");
else if ("azure".equals(providerId)) visible = paramName.startsWith("storage.azure.");
else if ("gcs".equals(providerId))   visible = paramName.startsWith("storage.gcs.");
else if ("http".equals(providerId))  visible = paramName.startsWith("storage.http.");
else if ("file".equals(providerId))  visible = paramName.startsWith("storage.file.");
```

**4b. In-memory connectionParameters normalization at edit time.** This is needed because Wicket `MapModel` widgets read/write `storeInfo.getConnectionParameters()` directly, keyed by the factory's declared `Param.key` (now `storage.*`). If the catalog holds legacy keys, widgets see null values and the user would see an empty form for an existing store. Fix: rewrite the existing `onBeforeRender` override to first normalize the store's connectionParameters map in place — substituting every legacy-prefixed key with its `storage.*` form (value preserved) — then fall through to the original providerId read and event dispatch.

```java
@Override
protected void onBeforeRender() {
    DataStoreInfo storeInfo = (DataStoreInfo) super.storeEditForm.getModelObject();
    Map<String, Serializable> params = storeInfo.getConnectionParameters();
    Map<String, Serializable> rewritten = new LinkedHashMap<>(params.size());
    params.forEach((k, v) -> rewritten.put(RangeReaderConfig.normalizeKey(k), v));
    params.clear();
    params.putAll(rewritten);
    super.onBeforeRender();
    String providerId = (String) params.get(RANGEREADER_PROVIDER_ID.key);
    sendEvent(new RangeReaderChangedEvent(providerId, null));
}
```

Result: when the user first opens an existing store's edit page, their form fields populate correctly. If they save, GeoServer persists the (now `storage.*`-keyed) map back to the catalog — effectively a passive, opt-in migration without any active rewrite step. If they never save, the catalog stays long-keyed and the reader still works via the bridge-side normalization in Section 3.

**4c. Localization resource keys** (~line 251): the `providerIdLabelModel` builds resource keys like `"%s.%s".formatted(RANGEREADER_PROVIDER_ID.key, providerId)`. With the new key, this becomes `storage.provider.s3`, `storage.provider.azure`, etc. Update the corresponding `.properties` resource file entries accordingly. If there's concern about distant downstream translations, the old resource keys can be kept alongside the new ones for a release or two.

### 5. Deprecation signal

`RangeReaderConfig.normalizeKey` emits `log.warn` the first time each distinct legacy key is seen per JVM. `ConcurrentHashMap.newKeySet()` serves as a thread-safe deduplication set so long-running GeoServer instances don't log-spam.

## Tests

### `tileverse-rangereader/core`

- `RangeReaderConfigTest` additions:
  - Set via legacy key, read via canonical `storage.*` key → same value (and inversely).
  - `fromProperties` with legacy `URI_KEY` / `PROVIDER_ID_KEY` parses correctly.
  - Legacy-key `Properties` → `fromProperties` → `toProperties` emits `storage.*` keys only.
  - Legacy key triggers WARN exactly once (log-capture test, e.g. via Logback ListAppender).
- New cross-provider guard test: iterate every `RangeReaderProvider` via the SPI (`ServiceLoader`), and assert every declared parameter's key **starts with** `KEY_PREFIX` and does **not** start with `LEGACY_KEY_PREFIX`. Catches any future straggler.

### Per-provider tests

One new test per provider (e.g. `S3RangeReaderProviderTest`, `AzureBlobRangeReaderProviderTest`, `HttpRangeReaderProviderTest`, `GoogleCloudStorageRangeReaderProviderTest`): build a `RangeReaderConfig` using only legacy-prefixed keys, invoke the provider, assert the reader is created and functional.

### GeoTools `PMTilesDataStoreFactoryTest`

- Existing tests continue to pass after the tileverse bump.
- New test: `createDataStore(Map)` with a map containing legacy-prefixed entries produces a working store (simulates catalog read of an existing store).

### GeoServer pmtiles-store community plugin

- New test covering legacy catalog behavior end-to-end in the plugin:
  - Simulate a `DataStoreInfo.connectionParameters` populated entirely with legacy keys (representative of an upgrade in place), verify `createDataStore` succeeds and returns a functional store.
  - For the edit panel: with a legacy-key `DataStoreInfo`, render `PMTilesDataStoreEditPanel` via a Wicket tester and assert all expected form widgets have populated values (this exercises the `onBeforeRender` map rewrite in 4b).

## Files to change

### tileverse-rangereader

- `core/src/main/java/io/tileverse/rangereader/spi/RangeReaderConfig.java` — add `KEY_PREFIX`, `LEGACY_KEY_PREFIX`, `normalizeKey`, `normalizeKeys`, update `URI_KEY`, `PROVIDER_ID_KEY`, legacy constants, update `setParameter`/`getParameter`/`fromProperties`.
- `core/src/main/java/io/tileverse/rangereader/spi/CachingProviderHelper.java` — `storage.caching.*` keys.
- `core/src/main/java/io/tileverse/rangereader/http/HttpRangeReaderProvider.java` — `storage.http.*` keys.
- `core/src/main/java/io/tileverse/rangereader/file/FileRangeReaderProvider.java` — `storage.file.*` keys.
- `s3/src/main/java/io/tileverse/rangereader/s3/S3RangeReaderProvider.java` — `storage.s3.*` keys.
- `azure/src/main/java/io/tileverse/rangereader/azure/AzureBlobRangeReaderProvider.java` — `storage.azure.*` keys.
- `gcs/src/main/java/io/tileverse/rangereader/gcs/GoogleCloudStorageRangeReaderProvider.java` — `storage.gcs.*` keys.
- `core/src/test/java/io/tileverse/rangereader/spi/RangeReaderConfigTest.java` — normalization + back-compat tests + SPI guard test.
- Per-provider test classes — one back-compat test each.

### geotools (branch `tileverse_upgrade`)

- `modules/unsupported/pmtiles/src/main/java/org/geotools/tileverse/rangereader/RangeReaderParams.java` — normalize input map in `toProperties`.
- `modules/unsupported/pmtiles/src/test/java/org/geotools/pmtiles/store/PMTilesDataStoreFactoryTest.java` — new legacy-keys test.

### geoserver (branch `tileverse_upgrade`)

- `src/community/pmtiles-store/src/main/java/org/geoserver/pmtiles/web/data/PMTilesDataStoreEditPanel.java` — `storage.*` prefix `startsWith` checks, `onBeforeRender` map normalization, localization key updates.
- Nearest community-plugin test — legacy `DataStoreInfo.connectionParameters` test.

## Rollout order

The three changes are backwards-compatible in isolation, so intermediate states don't break any downstream catalog:

1. Merge tileverse change (`storage.*` keys + normalization). Publish a snapshot.
2. Bump tileverse dep in geotools `tileverse_upgrade` branch; merge bridge fix.
3. Bump geotools dep in geoserver `tileverse_upgrade` branch; merge edit panel + tests.

## Out of scope

- Environment variable names like `IO_TILEVERSE_RANGEREADER_AZURE` (separate concern; not parameter keys).
- The `tileverse-parquet/AvroMaterializer_gap_plan.md` work on the current branch.
- Renaming `Param` option identifiers in any unrelated modules.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| A provider is missed during rename | SPI-wide test iterates `RangeReaderProvider` instances and asserts every key starts with `KEY_PREFIX` and none start with `LEGACY_KEY_PREFIX` |
| Log spam in long-running GeoServer | Per-key dedupe set; each distinct legacy key warns at most once per JVM |
| Downstream code reads `RangeReaderConfig.URI_KEY` as a string literal and persists it | New value (`storage.uri`) is valid; `fromProperties` accepts both forms |
| `param.lookUp` in GeoTools misses legacy entries | Bridge normalizes the whole map once, so every `param.key` lookup hits a normalized map |
| Parameter name collisions across GeoTools factories | `storage.*` double namespace (e.g. `storage.s3.region`) is near-unique globally; no need for a discriminator param |
