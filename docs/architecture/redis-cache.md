# Redis 快取架構設計

| 項目 | 內容 |
|------|------|
| **文件版本** | v2.0（簡化版，使用 Spring @Cacheable） |
| **建立日期** | 2026-09-17 |

---

## 1. 架構概覽

### 1.1 採用方案：Spring @Cacheable

使用 Spring Framework 內建的快取抽象，無需自訂快取服務：

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Cache Abstraction                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  @Cacheable  │  @CacheEvict  │  @CachePut          │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Redis Cache Manager                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  categories (30min)  │  tickets (5min)              │   │
│  │  users (10min)       │  dashboard (1min)            │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                         Redis                                │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 優點

| 優點 | 說明 |
|------|------|
| **程式碼簡潔** | 使用註解即可，無需自訂服務 |
| **無學習曲線** | Spring 標準功能，文件充足 |
| **易於測試** | 可用 Mockito 輕鬆 mock |
| **彈性擴展** | 可替換快取實現（Redis → EhCache 等） |

### 1.3 缺點與應對

| 缺點 | 應對方式 |
|------|----------|
| 不支援動態 Key | Categories 使用固定 Key，不分頁 |
| 功能較基本 | 足夠 95% 的使用場景 |

---

## 2. 快取 Key 設計

### 2.1 固定 Key 策略

Categories 使用固定 Key，理由：

| 設計決策 | 說明 |
|----------|------|
| **固定 Key** | `category:active:list` |
| **不分頁快取** | Categories 通常不超過一頁 |
| **allEntries 清空** | 使用 `@CacheEvict(allEntries = true)` |

### 2.2 Key 命名

```
{cacheName}:{id or identifier}

範例：
- categories::category:active:list  # Active Categories 列表
- tickets::550e8400                 # 特定工單詳情
- users::123e4567                   # 特定使用者資料
- dashboard::dashboard:summary      # Dashboard 統計
```

### 2.3 Key 對照表

| Cache Name | Key 產生方式 | TTL |
|------------|--------------|-----|
| `categories` | 固定 `category:active:list` | 30 分鐘 |
| `tickets` | 動態 `#ticketId` | 5 分鐘 |
| `users` | 動態 `#userId` | 10 分鐘 |
| `dashboard` | 固定 `dashboard:summary` | 1 分鐘 |

---

## 3. TTL 設計

### 3.1 TTL 策略

| Cache | TTL | 理由 |
|-------|-----|------|
| `categories` | 30 分鐘 | 讀多寫少，Admin 很少修改 |
| `tickets` | 5 分鐘 | 使用者可能重複查看同一張工單 |
| `users` | 10 分鐘 | 頻繁讀取（顯示作者名稱） |
| `dashboard` | 1 分鐘 | 統計查詢較耗時，可接受短暫延遲 |

### 3.2 CacheConfig.java

```java
@Configuration
@EnableCaching
public class CacheConfig {
    
    private final RedisConnectionFactory redisConnectionFactory;
    
    public CacheConfig(RedisConnectionFactory redisConnectionFactory) {
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Bean
    public RedisCacheManager cacheManager() {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            .entryTtl(Duration.ofMinutes(10));  // 預設 10 分鐘

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // Categories: 30 分鐘
        cacheConfigurations.put("categories", 
            defaultConfig.entryTtl(Duration.ofMinutes(30)));
        
        // Tickets: 5 分鐘
        cacheConfigurations.put("tickets", 
            defaultConfig.entryTtl(Duration.ofMinutes(5)));
        
        // Users: 10 分鐘
        cacheConfigurations.put("users", 
            defaultConfig.entryTtl(Duration.ofMinutes(10)));
        
        // Dashboard: 1 分鐘
        cacheConfigurations.put("dashboard", 
            defaultConfig.entryTtl(Duration.ofMinutes(1)));

        return RedisCacheManager.builder(redisConnectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .build();
    }
}
```

---

## 4. 服務整合設計

### 4.1 CategoryServiceImpl

```java
@Service
@Transactional
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryServiceImpl(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * 取得啟用的分類列表（使用快取）
     */
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "categories", key = "'category:active:list'")
    public PageResponse<CategorySummaryResponse> getActiveCategories(Pageable pageable) {
        Page<Category> page = categoryRepository.findAll(
            CategorySpecification.withFilters(true, null),
            pageable
        );
        return PageResponse.from(page, CategorySummaryResponse::from);
    }

    /**
     * 建立分類（清除快取）
     */
    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        // ... 建立邏輯
    }

    /**
     * 更新分類（清除快取）
     */
    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse updateCategory(UUID id, UpdateCategoryRequest request) {
        // ... 更新邏輯
    }

    /**
     * 停用分類（清除快取）
     */
    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse deactivateCategory(UUID id) {
        // ... 停用邏輯
    }

    /**
     * 啟用分類（清除快取）
     */
    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse activateCategory(UUID id) {
        // ... 啟用邏輯
    }
}
```

### 4.2 TicketServiceImpl

```java
@Service
@Transactional
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;

    public TicketServiceImpl(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    /**
     * 取得工單詳情（使用快取）
     */
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "tickets", key = "#ticketId")
    public TicketResponse getTicketById(UUID ticketId) {
        // ... 查詢邏輯
    }

    /**
     * 更新工單（清除快取）
     */
    @Override
    @CacheEvict(value = "tickets", key = "#ticketId")
    public TicketResponse updateTicket(UUID ticketId, UpdateTicketRequest request) {
        // ... 更新邏輯
    }
}
```

### 4.3 UserServiceImpl

```java
@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserServiceRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 取得使用者資料（使用快取）
     */
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "users", key = "#userId")
    public UserResponse getUserById(UUID userId) {
        // ... 查詢邏輯
    }

    /**
     * 更新使用者（清除快取）
     */
    @Override
    @CacheEvict(value = "users", key = "#userId")
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        // ... 更新邏輯
    }
}
```

### 4.4 DashboardService

```java
@Service
public class DashboardService {

    private final TicketRepository ticketRepository;

    public DashboardService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    /**
     * 取得 Dashboard 統計（使用快取，TTL 1 分鐘）
     */
    @Cacheable(value = "dashboard", key = "'dashboard:summary'")
    public DashboardSummary getSummary() {
        // ... 統計邏輯
    }
}
```

---

## 5. 錯誤處理設計

### 5.1 Redis 不可用時的降級策略

Spring Cache 預設會在 Redis 不可用時拋出異常。為確保系統可用性，需配置降級：

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            .entryTtl(Duration.ofMinutes(10))
            // Redis 不可用時不拋例外，降級為 null（由 caller 處理）
            .disableCachingNullValues();

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaultConfig)
            .build();
    }
}
```

### 5.2 Service 層降級處理

```java
@Override
@Transactional(readOnly = true)
public TicketResponse getTicketById(UUID ticketId) {
    try {
        // 嘗試從快取取得
        TicketResponse cached = ticketCacheService.get(ticketId);
        if (cached != null) {
            return cached;
        }
    } catch (CacheException e) {
        // Redis 不可用，降級為直接查 DB
        log.warn("Cache unavailable, falling back to database", e);
    }
    
    // 直接查 DB
    return loadTicketFromDatabase(ticketId);
}
```

---

## 6. 測試設計

### 6.1 單元測試策略

使用 Mockito 測試快取行為，不測試 Redis 本身：

```java
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    @Test
    void getActiveCategories_CacheHit() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        PageResponse<CategorySummaryResponse> cachedResponse = mock(PageResponse.class);
        
        // When
        var response = categoryService.getActiveCategories(pageable);
        
        // Then
        assertNotNull(response);
        // 驗證快取相關行為
    }
}
```

### 6.2 整合測試策略

使用 Testcontainers 測試實際 Redis：

```java
@Testcontainers
@RedisCacheIntegrationTest
class CacheIntegrationTest {

    @Container
    static RedisContainer<?> redis = new RedisContainer<>("redis:7");
    
    @Test
    void categoriesCached_SecondQueryNoDbHit() {
        // 第一次查詢 → DB
        categoryService.getActiveCategories(pageable);
        
        // 第二次查詢 → Cache Hit
        categoryService.getActiveCategories(pageable);
        
        // 驗證只有一次 DB 查詢
    }
}
```

---

## 7. 刪除的檔案

以下自訂快取服務檔案將被刪除：

| 檔案 | 刪除原因 |
|------|----------|
| `common/cache/CacheService.java` | 使用 Spring @Cacheable 替代 |
| `common/cache/CacheServiceImpl.java` | 使用 Spring @Cacheable 替代 |
| `common/cache/CacheKeys.java` | Spring 自動產生 Key |
| `common/cache/CacheTtl.java` | TTL 在 CacheConfig 中設定 |

---

## 8. 遷移步驟

### Phase 1: 更新 CacheConfig

1. 修改 `CacheConfig.java` 設定各快取的 TTL
2. 驗證 Redis 連線正常

### Phase 2: CategoryServiceImpl 重構

1. 移除 `CacheService` 依賴
2. 新增 `@Cacheable`、`@CacheEvict` 註解
3. 更新單元測試
4. 驗證功能正常

### Phase 3: 其他 Service 重構

1. TicketServiceImpl 重構
2. UserServiceImpl 重構
3. DashboardService 重構

### Phase 4: 刪除自訂快取服務

1. 刪除 `common/cache/` 目錄下的檔案
2. 執行完整測試

### Phase 5: 清理文件

1. 更新需求文件
2. 更新架構文件
3. 更新實作文件

---

## 9. 附錄

### A. Spring Cache 註解說明

| 註解 | 說明 |
|------|------|
| `@EnableCaching` | 在 Configuration 類上啟用快取 |
| `@Cacheable` | 標記方法結果可被快取 |
| `@CacheEvict` | 標記方法執行後清除快取 |
| `@CachePut` | 標記方法執行後更新快取（不改變回傳值） |

### B. SpEL 表達式

| 表達式 | 說明 |
|--------|------|
| `#id` | 方法參數 named `id` |
| `#ticketId` | 方法參數 named `ticketId` |
| `'fixed-key'` | 字串常值（需單引號） |
| `#result.id` | 回傳物件的 `id` 屬性 |

### C. 參考資源

- [Spring Cache 文件](https://docs.spring.io/spring-framework/reference/integration/cache.html)
- [Spring Data Redis Cache](https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/#cache)
