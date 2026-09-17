# Redis 快取功能實作手冊

| 項目 | 內容 |
|------|------|
| **文件版本** | v2.0（簡化版，使用 Spring @Cacheable） |
| **建立日期** | 2026-09-17 |

---

## 實作摘要

### 設計變更

| 項目 | 修正前（複雜） | 修正後（簡化） |
|------|----------------|----------------|
| **Key** | 動態（分頁參數） | 固定 `category:active:list` |
| **分頁** | 每頁獨立快取 | 不快取分頁結果 |
| **失效** | Pattern 刪除多個 key | `@CacheEvict(allEntries = true)` |
| **服務** | 自訂 CacheService | Spring `@Cacheable` |

### 刪除的檔案

```
common/cache/
├── CacheService.java        # 刪除
├── CacheServiceImpl.java    # 刪除
├── CacheKeys.java           # 刪除
└── CacheTtl.java           # 刪除
```

---

## Phase 1: 更新 CacheConfig

**目標**：設定各快取的 TTL

### 1.1 檢查現有 CacheConfig

位置：`src/main/java/com/pk/support_ticket_api/common/config/CacheConfig.java`

### 1.2 更新 CacheConfig

```java
package com.pk.support_ticket_api.common.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 預設快取設定
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues();

        // 各快取的 TTL 設定
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // Categories: 30 分鐘（讀多寫少）
        cacheConfigurations.put("categories", 
            defaultConfig.entryTtl(Duration.ofMinutes(30)));
        
        // Tickets: 5 分鐘（可能重複查看）
        cacheConfigurations.put("tickets", 
            defaultConfig.entryTtl(Duration.ofMinutes(5)));
        
        // Users: 10 分鐘（頻繁讀取）
        cacheConfigurations.put("users", 
            defaultConfig.entryTtl(Duration.ofMinutes(10)));
        
        // Dashboard: 1 分鐘（統計查詢）
        cacheConfigurations.put("dashboard", 
            defaultConfig.entryTtl(Duration.ofMinutes(1)));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .build();
    }
}
```

### 1.3 驗證步驟

```bash
./mvnw clean compile
```

---

## Phase 2: CategoryServiceImpl 重構

**目標**：移除自訂 CacheService，改用 Spring @Cacheable

### 2.1 完整重寫 CategoryServiceImpl

```java
package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.domain.CategorySpecification;
import com.pk.support_ticket_api.categories.dto.CategoryResponse;
import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.categories.dto.CreateCategoryRequest;
import com.pk.support_ticket_api.categories.dto.UpdateCategoryRequest;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.common.exception.BusinessRuleException;
import com.pk.support_ticket_api.common.exception.ConflictException;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import com.pk.support_ticket_api.common.response.PageResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryServiceImpl(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

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

    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        if (categoryRepository.existsByName(request.name())) {
            throw new ConflictException("Category name already exists: " + request.name());
        }

        Category category = new Category();
        category.setName(request.name());
        category.setDescription(request.description());
        category.setSlaHoursLow(request.slaHoursLow());
        category.setSlaHoursMedium(request.slaHoursMedium());
        category.setSlaHoursHigh(request.slaHoursHigh());
        category.setSlaHoursUrgent(request.slaHoursUrgent());

        Category saved = categoryRepository.save(category);
        return CategoryResponse.from(saved);
    }

    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse updateCategory(UUID id, UpdateCategoryRequest request) {
        Category category = findCategoryById(id);

        if (request.name() != null && !request.name().isBlank()) {
            if (categoryRepository.existsByNameAndIdNot(request.name(), id)) {
                throw new ConflictException("Category name already exists: " + request.name());
            }
            category.setName(request.name());
        }

        if (request.description() != null) {
            category.setDescription(request.description());
        }

        if (request.slaHoursLow() != null) {
            category.setSlaHoursLow(request.slaHoursLow());
        }
        if (request.slaHoursMedium() != null) {
            category.setSlaHoursMedium(request.slaHoursMedium());
        }
        if (request.slaHoursHigh() != null) {
            category.setSlaHoursHigh(request.slaHoursHigh());
        }
        if (request.slaHoursUrgent() != null) {
            category.setSlaHoursUrgent(request.slaHoursUrgent());
        }

        Category saved = categoryRepository.save(category);
        return CategoryResponse.from(saved);
    }

    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse deactivateCategory(UUID id) {
        Category category = findCategoryById(id);

        if (!category.getActive()) {
            throw new BusinessRuleException("Category is already deactivated");
        }

        category.setActive(false);
        Category saved = categoryRepository.save(category);
        return CategoryResponse.from(saved);
    }

    @Override
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse activateCategory(UUID id) {
        Category category = findCategoryById(id);

        if (category.getActive()) {
            throw new BusinessRuleException("Category is already active");
        }

        category.setActive(true);
        Category saved = categoryRepository.save(category);
        return CategoryResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponse getCategoryById(UUID id) {
        Category category = findCategoryById(id);
        return CategoryResponse.from(category);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> getAllCategories(
            Boolean active,
            String keyword,
            Pageable pageable
    ) {
        Page<Category> page = categoryRepository.findAll(
            CategorySpecification.withFilters(active, keyword),
            pageable
        );

        return PageResponse.from(
            page,
            CategoryResponse::from
        );
    }

    private Category findCategoryById(UUID id) {
        return categoryRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Category not found with id: " + id
            ));
    }
}
```

### 2.2 刪除相依性

1. 移除 `CacheService` 依賴
2. 移除 `CacheKeys` 引用
3. 移除 `CacheTtl` 引用

### 2.3 驗證步驟

```bash
./mvnw test -Dtest=CategoryServiceTest
```

---

## Phase 3: 其他 Service 重構

### 3.1 TicketServiceImpl

為 `getTicketById()` 添加 `@Cacheable`，為更新方法添加 `@CacheEvict`：

```java
@Override
@Transactional(readOnly = true)
@Cacheable(value = "tickets", key = "#ticketId")
public TicketResponse getTicketById(UUID ticketId) {
    Ticket ticket = findTicketById(ticketId);
    return enrichTicketResponse(ticket);
}

@Override
@CacheEvict(value = "tickets", key = "#ticketId")
public TicketResponse updateTicket(UUID ticketId, UpdateTicketRequest request) {
    // ... 更新邏輯
}
```

### 3.2 UserServiceImpl

為 `getUserById()` 添加 `@Cacheable`，為更新方法添加 `@CacheEvict`：

```java
@Override
@Transactional(readOnly = true)
@Cacheable(value = "users", key = "#userId")
public UserResponse getUserById(UUID userId) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    return UserResponse.from(user);
}

@Override
@CacheEvict(value = "users", key = "#userId")
public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
    // ... 更新邏輯
}
```

### 3.3 DashboardService

為 `getSummary()` 添加 `@Cacheable`：

```java
@Override
@Cacheable(value = "dashboard", key = "'dashboard:summary'")
public DashboardSummary getSummary() {
    // ... 統計邏輯
}
```

---

## Phase 4: 刪除自訂快取服務

### 4.1 刪除檔案

```bash
rm src/main/java/com/pk/support_ticket_api/common/cache/CacheService.java
rm src/main/java/com/pk/support_ticket_api/common/cache/CacheServiceImpl.java
rm src/main/java/com/pk/support_ticket_api/common/cache/CacheKeys.java
rm src/main/java/com/pk/support_ticket_api/common/cache/CacheTtl.java
```

### 4.2 驗證編譯

```bash
./mvnw clean compile
```

---

## Phase 5: 完整測試

### 5.1 執行所有測試

```bash
./mvnw test
```

### 5.2 預期結果

- 所有單元測試通過
- CategoryServiceTest、UserServiceTest、TicketServiceTest 等測試正常

---

## 常見問題

### Q1: 快取沒有生效？

檢查：
1. `@EnableCaching` 是否有加在 Configuration 類上
2. `CacheManager` bean 是否正確定義
3. Redis 是否正常連線

### Q2: @CacheEvict 和 @CacheEvict(allEntries = true) 的差別？

| 註解 | 行為 |
|------|------|
| `@CacheEvict(key = "#id")` | 刪除特定 key |
| `@CacheEvict(allEntries = true)` | 刪除整個 cache 的所有 key |

Categories 使用 `allEntries = true` 是因為只有一個固定 key。

### Q3: 如何手動清除快取？

```bash
# 透過 Redis CLI
redis-cli
> KEYS "categories::*"
> DEL "categories::category:active:list"
```

---

## 驗收清單

- [ ] CacheConfig.java TTL 設定正確
- [ ] CategoryServiceImpl 使用 @Cacheable/@CacheEvict
- [ ] 自訂快取服務檔案已刪除
- [ ] `./mvnw test` 全部通過
- [ ] 快取失效邏輯正確（新增/更新/刪除時清除）
