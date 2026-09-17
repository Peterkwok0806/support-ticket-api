# Redis 快取架構設計

## 1. 設計原則

### 1.1 核心原則

| 原則 | 說明 |
|------|------|
| **Simple** | 使用 Spring Data Redis 的 `StringRedisTemplate`，不做过度封装 |
| **Resilient** | Redis 故障時降級為直接查 DB，不阻斷業務 |
| **Consistent** | 寫入時主動失效快取，保持最終一致性 |
| **Observable** | 記錄快取命中/未命中日誌，便於監控 |

### 1.2 ⚠️ 動態 Key 設計原則

> **帶分頁/篩選參數的查詢，Key 必須動態包含這些參數！**

❌ **錯誤示範**（固定 Key）：
```
cacheService.get("category:active:list", ...)  // ❌ 固定 Key
page=0 → 載入第1頁 → 快取
page=1 → 命中快取 → 回傳第1頁的錯誤資料！
```

✅ **正確做法**（動態 Key）：
```
cacheService.get(CacheKeys.activeCategories(pageable), ...)  // ✅ 動態 Key
Key: "category:active:list:p0:s20:createdAt_desc"
Key: "category:active:list:p1:s20:createdAt_desc"
```

**失效時使用 Pattern**：`evictByPattern("category:active:list:*")`

### 1.3 架構位置

```
┌─────────────────────────────────────────────────────────────┐
│                      Controller Layer                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      Service Layer                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │  Category   │  │   Ticket    │  │    User     │          │
│  │   Service   │  │   Service   │  │   Service   │          │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘          │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                   CacheService (AOP 或封裝)                  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   StringRedisTemplate                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                        Redis                                 │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. CacheService 設計

### 2.1 介面定義

```java
public interface CacheService {
    
    /**
     * 取得快取值，不存在時透過 loader 載入並快取
     */
    <T> T get(String key, Class<T> type, Supplier<T> loader);
    
    <T> T get(String key, ParameterizedTypeReference<T> type, Supplier<T> loader);
    
    /**
     * 刪除單一快取 key
     */
    void evict(String key);
    
    /**
     * 刪除符合 pattern 的所有 key
     */
    void evictByPattern(String pattern);
}
```

### 2.2 實作策略

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheServiceImpl implements CacheService {
    
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    
    @Override
    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        String cached = redisTemplate.opsForValue().get(key);
        
        if (cached != null) {
            log.debug("[CACHE] HIT: {}", key);
            return deserialize(cached, type);
        }
        
        log.debug("[CACHE] MISS: {}", key);
        T value = loader.get();
        
        if (value != null) {
            String serialized = serialize(value);
            // TTL 由 caller 決定
            redisTemplate.opsForValue().set(key, serialized);
        }
        
        return value;
    }
    
    @Override
    public void evict(String key) {
        redisTemplate.delete(key);
        log.debug("[CACHE] EVICT: {}", key);
    }
    
    @Override
    public void evictByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.debug("[CACHE] EVICT PATTERN: {} ({} keys)", pattern, keys.size());
        }
    }
}
```

---

## 3. CacheKeys 常數

### 3.1 定義

```java
public final class CacheKeys {
    
    private CacheKeys() {}
    
    // Category
    public static final String CATEGORY_ACTIVE_LIST = "category:active:list";
    
    // Ticket
    public static String ticket(Long id) {
        return "ticket:" + id;
    }
    
    // User
    public static String user(Long id) {
        return "user:" + id;
    }
    
    // Dashboard
    public static final String DASHBOARD_SUMMARY = "dashboard:summary";
}
```

### 3.2 TTL 設定

```java
public final class CacheTtl {
    
    private CacheTtl() {}
    
    public static final Duration ACTIVE_CATEGORIES = Duration.ofMinutes(30);
    public static final Duration TICKET_DETAIL = Duration.ofMinutes(5);
    public static final Duration DASHBOARD_SUMMARY = Duration.ofMinutes(1);
    public static final Duration USER_PROFILE = Duration.ofMinutes(10);
}
```

---

## 4. Redis 設定

### 4.1 Configuration

```java
@Configuration
public class RedisConfig {
    
    @Bean
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
```

### 4.2 application.yml

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0
          max-wait: 2000ms
```

---

## 5. 降級策略

### 5.1 設計

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ResilientCacheService implements CacheService {
    
    private final CacheService delegate;
    
    @Override
    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        try {
            return delegate.get(key, type, loader);
        } catch (RedisConnectionException e) {
            log.warn("[CACHE] Redis unavailable, falling back to DB: {}", e.getMessage());
            return loader.get();
        }
    }
    
    @Override
    public void evict(String key) {
        try {
            delegate.evict(key);
        } catch (RedisConnectionException e) {
            log.warn("[CACHE] Failed to evict, Redis may be unavailable: {}", key);
        }
    }
    
    // ... 其他方法類似
}
```

### 5.2 觸發條件

| 情境 | 處理 |
|------|------|
| Redis 連線超時 | 降級為 DB |
| Redis 連線拒絕 | 降級為 DB |
| 序列化/反序列化失敗 | 刪除該筆快取，回傳 DB 結果 |

---

## 6. 整合模式

### 6.1 Service 整合範例（Category）

```java
@Service
@RequiredArgsConstructor
@Transactional
public class CategoryServiceImpl implements CategoryService {
    
    private final CategoryRepository categoryRepository;
    private final CacheService cacheService;
    
    @Override
    @Transactional(readOnly = true)
    public PageResponse<CategorySummaryResponse> getActiveCategories(Pageable pageable) {
        String key = CacheKeys.CATEGORY_ACTIVE_LIST;
        
        return cacheService.get(key, PageResponse.class, () -> {
            Page<Category> page = categoryRepository.findAll(
                CategorySpecification.withFilters(true, null),
                pageable
            );
            return PageResponse.from(page, CategorySummaryResponse::from);
        });
    }
    
    @Override
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        // ... 建立邏輯
        Category saved = categoryRepository.save(category);
        
        // 失效快取
        cacheService.evict(CacheKeys.CATEGORY_ACTIVE_LIST);
        
        return CategoryResponse.from(saved);
    }
    
    @Override
    public CategoryResponse updateCategory(UUID id, UpdateCategoryRequest request) {
        // ... 更新邏輯
        Category saved = categoryRepository.save(category);
        
        // 失效快取
        cacheService.evict(CacheKeys.CATEGORY_ACTIVE_LIST);
        
        return CategoryResponse.from(saved);
    }
}
```

### 6.2 關注點分離

| 職責 | 位置 |
|------|------|
| 業務邏輯 | Service 類別 |
| 快取讀取/寫入 | CacheService |
| Key 管理 | CacheKeys |
| TTL 管理 | CacheTtl |

---

## 7. 監控指標

### 7.1 Logback 設定（可選）

```xml
<logger name="com.pk.support_ticket_api.common.cache" level="DEBUG"/>
```

### 7.2 建議的監控指標

| 指標 | 說明 |
|------|------|
| `cache.hit.rate` | 快取命中率 |
| `cache.eviction.count` | 失效次數 |
| `cache.latency` | 快取讀取延遲 |
| `redis.connection.active` | 活躍連線數 |

---

## 8. 測試策略

### 8.1 單元測試（無 Redis）

```java
@ExtendWith(MockitoExtension.class)
class CacheServiceTest {
    
    @Mock
    private StringRedisTemplate redisTemplate;
    
    @Mock
    private ValueOperations<String, String> valueOperations;
    
    private CacheService cacheService;
    
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new CacheServiceImpl(redisTemplate, new ObjectMapper());
    }
    
    @Test
    void shouldReturnCachedValue_whenCacheHit() {
        // given
        String key = "test:key";
        String cached = "\"value\"";
        when(valueOperations.get(key)).thenReturn(cached);
        
        // when
        String result = cacheService.get(key, String.class, () -> "loaded");
        
        // then
        assertThat(result).isEqualTo("value");
    }
    
    @Test
    void shouldLoadAndCacheValue_whenCacheMiss() {
        // given
        String key = "test:key";
        when(valueOperations.get(key)).thenReturn(null);
        
        // when
        String result = cacheService.get(key, String.class, () -> "loaded");
        
        // then
        assertThat(result).isEqualTo("loaded");
        verify(valueOperations).set(eq(key), anyString());
    }
}
```

### 8.2 整合測試（Testcontainers）

```java
@Testcontainers
@SpringBootTest
class CacheServiceIntegrationTest {
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
        .withExposedPorts(6379);
    
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
    
    @Autowired
    private CacheService cacheService;
    
    @Test
    void shouldExpireAfterTtl() throws InterruptedException {
        // given
        String key = "test:ttl";
        
        // when - 寫入時設定 1 秒 TTL
        cacheService.get(key, String.class, () -> "value");
        
        // then - 等待 TTL 過期
        Thread.sleep(2000);
        String result = cacheService.get(key, String.class, () -> "reloaded");
        
        assertThat(result).isEqualTo("reloaded");
    }
}
```

---

## 9. 部署考量

### 9.1 環境變數

| 變數 | 預設值 | 說明 |
|------|--------|------|
| `REDIS_HOST` | localhost | Redis 主機 |
| `REDIS_PORT` | 6379 | Redis 連接埠 |

### 9.2 健康檢查

Redis 狀態可透過 `/actuator/health` 的 `redis` indicator 確認。

---

## 10. 擴展計畫

### Phase 2（未來）

| 功能 | 說明 |
|------|------|
| 分散式鎖 | 防止快取擊穿（Cache Stampede） |
| 訂閱發布 | 跨實例快取失效 |
| Redis Cluster | 支援更大規模部署 |
