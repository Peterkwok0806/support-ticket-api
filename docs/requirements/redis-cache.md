# Redis 快取功能強化 — 需求計劃書

| 項目 | 內容 |
|------|------|
| **文件版本** | v1.0 |
| **建立日期** | 2026-09-17 |
| **預計工期** | 3-4 天 |
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

| 資料 | 快取 Key 格式 | TTL | 理由 |
|------|---------------|-----|------|
| **Active Categories** | `category:active:list` | 30 分鐘 | 讀多寫少，Admin 很少修改分類 |
| **Ticket Detail** | `ticket:{id}` | 5 分鐘 | 使用者可能重複查看同一張工單 |
| **Dashboard Summary** | `dashboard:summary` | 1 分鐘 | 統計查詢較耗時，可接受短暫延遲 |
| **User Profile** | `user:{id}` | 10 分鐘 | 頻繁讀取（顯示作者名稱） |

### 2.2 快取 Key 命名規範

```
{domain}:{scope}:{identifier}

範例：
- category:active:list          # Active 分類列表
- ticket:550e8400-e29b-41d4    # 特定工單詳情
- dashboard:summary             # 儀表板摘要
- user:123e4567-e89b-12d3       # 特定使用者資料
```

### 2.3 Cache-Aside 模式

採用 **Cache-Aside**（Lazy Loading）策略：

```
查詢流程：
1. 先查 Redis 快取
2. 快取命中 → 直接回傳
3. 快取未命中 → 查資料庫 → 寫入快取 → 回傳

更新流程：
1. 更新資料庫
2. 刪除快取（或更新快取）
```

**優點**：首次查詢才寫入，節省記憶體；更新時只清理快取，下次查詢自動重建。

---

## 3. 功能需求

### 3.1 共用快取服務

**新增檔案**：`common/cache/CacheService.java`

| 方法 | 說明 |
|------|------|
| `get(key, type, supplier)` | 取得快取值，不存在時透過 supplier 載入並快取 |
| `evict(key)` | 刪除單一快取 key |
| `evictByPattern(pattern)` | 刪除符合 pattern 的所有 key（如 `ticket:*`） |

### 3.2 Category 快取

| 項目 | 內容 |
|------|------|
| **快取 Key** | `category:active:list` |
| **快取資料** | `Page<CategorySummaryResponse>` |
| **TTL** | 30 分鐘（1800 秒） |
| **失效時機** | 建立、更新、刪除 Category 時 |

| 方法 | 修改內容 |
|------|----------|
| `getActiveCategories()` | 先查快取，無則查 DB 並寫入快取 |
| `createCategory()` | 建立後 evict `category:active:list` |
| `updateCategory()` | 更新後 evict `category:active:list` |
| `deactivateCategory()` | 停用後 evict `category:active:list` |
| `activateCategory()` | 啟用後 evict `category:active:list` |

### 3.3 Ticket Detail 快取

| 項目 | 內容 |
|------|------|
| **快取 Key** | `ticket:{ticketId}` |
| **快取資料** | `TicketResponse`（含 enrichment） |
| **TTL** | 5 分鐘（300 秒） |
| **失效時機** | 更新、狀態變更、指派、留言、新增附件時 |

| 方法 | 修改內容 |
|------|----------|
| `getTicketById()` | 先查快取，無則查 DB 並寫入快取 |
| `createTicket()` | 建立後 evict `ticket:{id}` |
| `updateTicket()` | 更新後 evict `ticket:{id}` |
| `updateStatus()` | 狀態變更後 evict `ticket:{id}` |
| `assignTicket()` | 指派後 evict `ticket:{id}` |

**注意**：搜尋列表（`getTickets`）**不**使用快取，因為篩選條件多樣，難以有效快取。

### 3.4 Dashboard Summary 快取

| 項目 | 內容 |
|------|------|
| **快取 Key** | `dashboard:summary` |
| **快取資料** | Dashboard 統計聚合結果 |
| **TTL** | 1 分鐘（60 秒） |
| **失效時機** | Ticket 建立、更新狀態時（可選：僅在被引用時被動失效） |

**Dashboard 統計內容**：

```java
public record DashboardSummary(
    long openCount,          // Open 狀態工單數
    long inProgressCount,    // In Progress 狀態工單數
    long overdueCount,       // 逾期工單數
    long resolvedTodayCount, // 今日已解決工單數
    Map<TicketPriority, Long> byPriority,   // 按優先級分組
    Map<String, Long> byCategory            // 按分類分組
)
```

### 3.5 User Profile 快取

| 項目 | 內容 |
|------|------|
| **快取 Key** | `user:{userId}` |
| **快取資料** | `UserResponse` |
| **TTL** | 10 分鐘（600 秒） |
| **失效時機** | 更新使用者資料時 |

| 方法 | 修改內容 |
|------|----------|
| `findById()` | 先查快取，無則查 DB 並寫入快取 |
| `updateUser()` | 更新後 evict `user:{userId}` |

---

## 4. 非功能需求

### 4.1 效能要求

| 指標 | 目標 |
|------|------|
| 快取讀取延遲 | < 5ms |
| 快取命中率（預期） | > 70% |
| Redis 連線池 | 預設 8 個連線 |

### 4.2 可觀測性

**日誌記錄**（可選開關）：

```
[CACHE] HIT   key=category:active:list
[CACHE] MISS  key=ticket:550e8400
[CACHE] EVICT key=category:active:list reason=update
```

### 4.3 錯誤處理

| 情境 | 處理方式 |
|------|----------|
| Redis 連線失敗 | 降級為直接查 DB（不拋例外，不阻斷業務） |
| 快取值序列化失敗 | 刪除該筆快取，下次重新載入 |
| 快取寫入失敗 | 記錄 warn 日誌，業務流程繼續 |

---

## 5. 架構設計

### 5.1 專案結構變更

```
src/main/java/com/pk/support_ticket_api/
├── common/
│   ├── cache/                          # [新增]
│   │   ├── CacheService.java           # 共用快取服務
│   │   └── CacheKeys.java              # Key 常數定義
│   └── config/
│       └── RedisConfig.java            # [新增] Redis 設定
```

### 5.2 快取服務介面

```java
public interface CacheService {
    <T> T get(String key, Class<T> type, Supplier<T> loader);
    <T> T get(String key, ParameterizedTypeReference<T> type, Supplier<T> loader);
    void evict(String key);
    void evictByPattern(String pattern);
}
```

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
```

---

## 6. 測試策略

### 6.1 單元測試

| 測試案例 | 驗證內容 |
|----------|----------|
| CacheService hit | 快取存在時不回呼 loader |
| CacheService miss | 快取不存在時呼叫 loader 並寫入快取 |
| CacheService evict | 刪除後下次查詢重新載入 |
| Evict on update | Category 更新後快取被清除 |

### 6.2 整合測試

使用 Testcontainers 測試實際 Redis：

```java
@Testcontainers
class CacheServiceIntegrationTest {
    @Container
    static RedisContainer<?> redis = new RedisContainer<>("redis:7");
}
```

| 測試案例 | 驗證內容 |
|----------|----------|
| TTL 正確 | 資料在 TTL 後自動消失 |
| 快取隔離 | 不同 key 的資料互不影響 |
| 並發安全 | 多執行緒同時寫入不會損壞資料 |

---

## 7. 實作計畫

### 7.1 Day 1：基礎設施

| 任務 | 預估工時 |
|------|----------|
| 新增 Redis 設定（RedisConfig.java） | 1h |
| 實作 CacheService 介面與實作 | 2h |
| 定義 CacheKeys 常數類別 | 1h |
| 單元測試 | 2h |

### 7.2 Day 2：Category + User 快取

| 任務 | 預估工時 |
|------|----------|
| CategoryService 整合快取 | 2h |
| UserService 整合快取 | 2h |
| 快取失效邏輯 | 1h |
| 單元測試 + 整合測試 | 3h |

### 7.3 Day 3：Ticket + Dashboard 快取

| 任務 | 預估工時 |
|------|----------|
| TicketService 整合快取 | 3h |
| Dashboard 統計 + 快取 | 2h |
| 單元測試 + 整合測試 | 3h |

### 7.4 Day 4：測試與文件

| 任務 | 預估工時 |
|------|----------|
| 完整整合測試 | 3h |
| 更新 API 文件（Swagger） | 1h |
| 更新 README / 操作手冊 | 1h |
| 效能驗證 | 3h |

---

## 8. 預計產出檔案

| 檔案 | 類型 | 說明 |
|------|------|------|
| `common/config/RedisConfig.java` | 新增 | Redis 連線設定 |
| `common/cache/CacheService.java` | 新增 | 快取服務介面 |
| `common/cache/CacheServiceImpl.java` | 新增 | 快取服務實作 |
| `common/cache/CacheKeys.java` | 新增 | Key 常數定義 |
| `categories/service/CategoryServiceImpl.java` | 修改 | 整合快取 |
| `users/service/UserServiceImpl.java` | 修改 | 整合快取 |
| `tickets/service/TicketServiceImpl.java` | 修改 | 整合快取 |
| `tickets/service/DashboardService.java` | 新增 | Dashboard 統計服務 |
| `*CacheServiceTest.java` | 新增 | 單元測試 |
| `*CacheServiceIntegrationTest.java` | 新增 | 整合測試 |

---

## 9. 風險與緩解

| 風險 | 可能性 | 影響 | 緩解措施 |
|------|--------|------|----------|
| Redis 不可用時影響效能 | 低 | 高 | 實作降級機制，直接查 DB |
| 快取與資料庫不一致 | 中 | 中 | 寫入時主動失效快取 |
| 快取 key 命名衝突 | 低 | 高 | 統一使用 CacheKeys 常數類別 |
| TTL 設定不合理 | 中 | 中 | 先用保守值，後續依監控調整 |

---

## 10. 替代方案考量

### 10.1 Write-Through vs Cache-Aside

| 方案 | 優點 | 缺點 |
|------|------|------|
| **Cache-Aside（選用）** | 簡單、記憶體效率高 | 首次查詢有兩個步驟 |
| Write-Through | 寫入時同步更新快取 | 記憶體佔用較高、寫入延遲 |

**結論**：採用 Cache-Aside，適合讀多寫少的場景。

### 10.2 快取過期策略

| 策略 | 適用場景 |
|------|----------|
| **TTL 過期（選用）** | Dashboard、User Profile |
| 主動失效 | Category、Ticket（寫入時清除） |

**結論**：Dashboard 單純用 TTL；其他資料寫入時主動失效 + TTL 兜底。

---

## 11. 驗收標準

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
| 9 | API 文件更新 | Swagger 可見快取相關資訊 |

---

## 12. 附錄

### A. 參考資源

- [Spring Data Redis 文件](https://docs.spring.io/spring-data/data-redis/docs/current/reference/html/)
- [Cache-Aside Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/cache-aside)
- [Redis Best Practices](https://redis.io/docs/manual/patterns/)

### B. 術語表

| 術語 | 說明 |
|------|------|
| Cache-Aside | 一種快取模式，先查快取，未命中再查 DB 並寫入快取 |
| TTL | Time To Live，快取存活的時間 |
| Evict | 刪除快取 |
| Cache Hit | 快取命中，直接從快取取得資料 |
| Cache Miss | 快取未命中，需要從 DB 載入 |
