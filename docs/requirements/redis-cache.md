# Redis 快取功能強化 — 需求計劃書

| 項目 | 內容 |
|------|------|
| **文件版本** | v2.0（簡化版，使用 Spring @Cacheable） |
| **建立日期** | 2026-09-17 |
| **預計工期** | 2-3 天 |
| **優先順序** | 中高 |

---

## 1. 目的與背景

### 1.1 問題描述

目前系統的查詢效能存在以下瓶頸：

| 瓶頸點 | 說明 |
|--------|------|
| **Category 列表** | 每個頁面都可能需要取得分類下拉選單，每次都打資料庫 |
| **Ticket Detail** | Agent/Customer 重複查看同一張工單時，無意義的重複查詢 |
| **Dashboard 統計** | 聚合查詢（count、分群）語句成本高，頻繁執行影響效能 |
| **User Profile** | 顯示作者名稱、代理人名稱時重複查詢 `users` 表 |

### 1.2 預期效益

| 效益 | 量化目標 |
|------|----------|
| 降低資料庫查詢量 | 熱門端點減少 60-80% 重複查詢 |
| 縮短回應時間 | P95 latency 降低 30-50% |
| 提升擴展性 | 支援更高併發使用者 |
| 減少資料庫負載 | 統計類查詢從即時改為快取結果 |

---

## 2. 快取策略設計

### 2.1 快取資料清單

| 資料 | 快取 Key | TTL | 理由 |
|------|----------|-----|------|
| **Active Categories** | `category:active:list` | 30 分鐘 | 讀多寫少，Categories 通常不超過一頁 |
| **Ticket Detail** | `ticket:{id}` | 5 分鐘 | 使用者可能重複查看同一張工單 |
| **Dashboard Summary** | `dashboard:summary` | 1 分鐘 | 統計查詢較耗時，可接受短暫延遲 |
| **User Profile** | `user:{id}` | 10 分鐘 | 頻繁讀取（顯示作者名稱） |

### 2.2 快取 Key 命名規範

```
{domain}:{scope}:{identifier}

範例：
- category:active:list          # Active 分類列表（固定 Key，Categories 不分頁）
- ticket:550e8400-e29b-41d4    # 特定工單詳情
- dashboard:summary             # 儀表板摘要
- user:123e4567-e89b-12d3       # 特定使用者資料
```

### 2.3 Cache-Aside 模式

採用 **Cache-Aside**（Lazy Loading）策略，使用 Spring `@Cacheable` 註解簡化實作：

```
查詢流程：
1. 先查 Redis 快取（@Cacheable）
2. 快取命中 → 直接回傳
3. 快取未命中 → 查資料庫 → 自動寫入快取 → 回傳

更新流程：
1. 更新資料庫
2. 刪除快取（@CacheEvict）
```

**優點**：使用 Spring 內建功能，程式碼簡潔，無需自訂快取服務。

### 2.4 設計原則

| 項目 | 說明 |
|------|------|
| **固定 Key** | Categories 使用固定 Key，不使用分頁參數動態擴展 |
| **不分頁快取** | Categories 通常不超過一頁，快取分頁結果無意義 |
| **簡單失效** | 使用 `@CacheEvict` 直接刪除單一 Key |
| **Spring 原生** | 使用 `@Cacheable`、`@CacheEvict`，不需自訂快取服務 |

---

## 3. 功能需求

### 3.1 Category 快取

| 項目 | 內容 |
|------|------|
| **快取 Key** | `category:active:list`（固定） |
| **快取資料** | `Page<CategorySummaryResponse>` |
| **TTL** | 30 分鐘（1800 秒） |
| **失效時機** | 建立、更新、刪除 Category 時 |

| 方法 | 註解 |
|------|------|
| `getActiveCategories()` | `@Cacheable("categories")` |
| `createCategory()` | `@CacheEvict(value = "categories", allEntries = true)` |
| `updateCategory()` | `@CacheEvict(value = "categories", allEntries = true)` |
| `deactivateCategory()` | `@CacheEvict(value = "categories", allEntries = true)` |
| `activateCategory()` | `@CacheEvict(value = "categories", allEntries = true)` |

### 3.2 Ticket Detail 快取

| 項目 | 內容 |
|------|------|
| **快取 Key** | `ticket:{ticketId}` |
| **快取資料** | `TicketResponse`（含 enrichment） |
| **TTL** | 5 分鐘（300 秒） |
| **失效時機** | 更新、狀態變更、指派、留言、新增附件時 |

| 方法 | 註解 |
|------|------|
| `getTicketById()` | `@Cacheable(cacheNames = "tickets", key = "#ticketId")` |
| `createTicket()` | `@CacheEvict(cacheNames = "tickets", key = "#result.id")` |
| `updateTicket()` | `@CacheEvict(cacheNames = "tickets", key = "#ticketId")` |
| `updateStatus()` | `@CacheEvict(cacheNames = "tickets", key = "#ticketId")` |
| `assignTicket()` | `@CacheEvict(cacheNames = "tickets", key = "#ticketId")` |

**注意**：搜尋列表（`getTickets`）**不**使用快取，因為篩選條件多樣，難以有效快取。

### 3.3 Dashboard Summary 快取

| 項目 | 內容 |
|------|------|
| **快取 Key** | `dashboard:summary` |
| **快取資料** | Dashboard 統計聚合結果 |
| **TTL** | 1 分鐘（60 秒） |
| **失效時機** | Ticket 建立、更新狀態時（被動失效） |

### 3.4 User Profile 快取

| 項目 | 內容 |
|------|------|
| **快取 Key** | `user:{userId}` |
| **快取資料** | `UserResponse` |
| **TTL** | 10 分鐘（600 秒） |
| **失效時機** | 更新使用者資料時 |

---

## 4. 非功能需求

### 4.1 效能要求

| 指標 | 目標 |
|------|------|
| 快取讀取延遲 | < 5ms |
| 快取命中率（預期） | > 70% |
| Redis 連線池 | 預設 8 個連線 |

### 4.2 錯誤處理

| 情境 | 處理方式 |
|------|----------|
| Redis 連線失敗 | 降級為直接查 DB（不拋例外，不阻斷業務） |
| 快取值序列化失敗 | 刪除該筆快取，下次重新載入 |
| 快取寫入失敗 | 記錄 warn 日誌，業務流程繼續 |

---

## 5. 架構設計

### 5.1 專案結構變更

使用 Spring 原生快取，只需新增/修改以下檔案：

```
src/main/java/com/pk/support_ticket_api/
├── common/
│   └── config/
│       └── CacheConfig.java            # [修改] 快取設定
├── categories/service/
│   └── CategoryServiceImpl.java        # [修改] 整合 @Cacheable
├── tickets/service/
│   └── TicketServiceImpl.java          # [修改] 整合 @Cacheable
├── users/service/
│   └── UserServiceImpl.java            # [修改] 整合 @Cacheable
└── dashboard/
    └── DashboardService.java            # [修改] 整合 @Cacheable
```

### 5.2 刪除的檔案

以下自訂快取服務檔案將被刪除：

- `common/cache/CacheService.java`
- `common/cache/CacheServiceImpl.java`
- `common/cache/CacheKeys.java`
- `common/cache/CacheTtl.java`

### 5.3 Redis 設定

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
  cache:
    type: redis
    redis:
      time-to-live: 600000  # 預設 10 分鐘，可依快取調整
```

### 5.4 CacheConfig.java

```java
@Configuration
@EnableCaching
public class CacheConfig {
    
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            .entryTtl(Duration.ofMinutes(10));

        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
            "categories", defaultConfig.entryTtl(Duration.ofMinutes(30)),
            "tickets", defaultConfig.entryTtl(Duration.ofMinutes(5)),
            "users", defaultConfig.entryTtl(Duration.ofMinutes(10)),
            "dashboard", defaultConfig.entryTtl(Duration.ofMinutes(1))
        );

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigs)
            .build();
    }
}
```

---

## 6. 測試策略

### 6.1 單元測試

使用 Mockito 測試快取行為（不測試 Redis 本身）：

| 測試案例 | 驗證內容 |
|----------|----------|
| @Cacheable hit | 快取存在時不回呼 loader |
| @Cacheable miss | 快取不存在時呼叫 loader 並寫入快取 |
| @CacheEvict | 刪除後下次查詢重新載入 |
| Evict on update | Category 更新後快取被清除 |

### 6.2 整合測試

使用 Testcontainers 測試實際 Redis：

```java
@Testcontainers
class CacheIntegrationTest {
    @Container
    static RedisContainer<?> redis = new RedisContainer<>("redis:7");
}
```

---

## 7. 實作計畫

### 7.1 Day 1：基礎設施 + Category 快取

| 任務 | 預估工時 |
|------|----------|
| 修改 CacheConfig.java（TTL 設定） | 1h |
| CategoryServiceImpl 改用 @Cacheable | 2h |
| CategoryServiceTest 更新 | 1h |
| 刪除自訂快取服務檔案 | 0.5h |

### 7.2 Day 2：Ticket + User + Dashboard 快取

| 任務 | 預估工時 |
|------|----------|
| TicketServiceImpl 改用 @Cacheable | 2h |
| UserServiceImpl 改用 @Cacheable | 1h |
| DashboardService 改用 @Cacheable | 1h |
| 單元測試更新 | 2h |
| 整合測試 | 2h |

### 7.3 Day 3：測試與驗證

| 任務 | 預估工時 |
|------|----------|
| 完整整合測試 | 3h |
| 更新 API 文件（Swagger） | 1h |
| 效能驗證 | 2h |

---

## 8. 預計產出檔案

| 檔案 | 類型 | 說明 |
|------|------|------|
| `common/config/CacheConfig.java` | 修改 | 快取 TTL 設定 |
| `categories/service/CategoryServiceImpl.java` | 修改 | 使用 @Cacheable/@CacheEvict |
| `tickets/service/TicketServiceImpl.java` | 修改 | 使用 @Cacheable/@CacheEvict |
| `users/service/UserServiceImpl.java` | 修改 | 使用 @Cacheable/@CacheEvict |
| `dashboard/DashboardService.java` | 修改 | 使用 @Cacheable/@CacheEvict |

**刪除檔案**：
- `common/cache/CacheService.java`
- `common/cache/CacheServiceImpl.java`
- `common/cache/CacheKeys.java`
- `common/cache/CacheTtl.java`

---

## 9. 風險與緩解

| 風險 | 可能性 | 影響 | 緩解措施 |
|------|--------|------|----------|
| Redis 不可用時影響效能 | 低 | 高 | 實作降級機制，直接查 DB |
| 快取與資料庫不一致 | 中 | 中 | 寫入時主動失效快取 |
| TTL 設定不合理 | 中 | 中 | 先用保守值，後續依監控調整 |

---

## 10. 驗收標準

| # | 標準 | 驗證方式 |
|---|------|----------|
| 1 | Category 列表查詢結果會被快取 | 第二次查詢不產生 SQL |
| 2 | 更新 Category 後快取失效 | 更新後查詢會重新載入 |
| 3 | Ticket 詳情會被快取 | 第二次查詢不產生 SQL |
| 4 | Ticket 更新後快取失效 | 更新後查詢會重新載入 |
| 5 | User Profile 會被快取 | 第二次查詢不產生 SQL |
| 6 | Dashboard 統計結果會被快取 | 第二次查詢不產生 SQL |
| 7 | Redis 不可用時系統仍正常運作 | 模擬 Redis 關閉 |
| 8 | 所有單元測試通過 | `./mvnw test` |

---

## 11. 附錄

### A. 參考資源

- [Spring Cache 文件](https://docs.spring.io/spring-framework/reference/integration/cache.html)
- [Spring Data Redis 文件](https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/)
- [Cache-Aside Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/cache-aside)

### B. 術語表

| 術語 | 說明 |
|------|------|
| @Cacheable | Spring 註解，標記方法結果可被快取 |
| @CacheEvict | Spring 註解，標記方法執行後清除快取 |
| Cache-Aside | 一種快取模式，先查快取，未命中再查 DB 並寫入快取 |
| TTL | Time To Live，快取存活的時間 |
| Evict | 刪除快取 |
