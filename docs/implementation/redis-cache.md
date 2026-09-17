# Redis 快取實作步驟

## 文件資訊

| 項目 | 內容 |
|------|------|
| 版本 | v1.0 |
| 日期 | 2026-09-17 |
| 狀態 | 待實作 |

---

## 實作順序概覽

```
Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6
```

| Phase | 工作內容 | 預估工時 |
|-------|----------|----------|
| Phase 1 | Redis 基礎設定 | 1h |
| Phase 2 | CacheService 核心服務 | 3h |
| Phase 3 | Category 快取整合 | 2h |
| Phase 4 | User Profile 快取整合 | 2h |
| Phase 5 | Ticket Detail 快取整合 | 3h |
| Phase 6 | Dashboard Summary 快取 | 2h |
| **總計** | | **13h（2 天）** |

---

## Phase 1：Redis 基礎設定

### Step 1.1：確認 Redis 依賴已存在

**檢查檔案**：`pom.xml`

確認已包含以下依賴：

```xml
<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

---

### Step 1.2：建立 RedisConfig.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/common/config/RedisConfig.java`

**新建內容**：

```java
package com.pk.support_ticket_api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 設定配置類
 * 設定 StringRedisTemplate 作為預設的 Redis 操作模板
 */
@Configuration
public class RedisConfig {

    /**
     * 建立 StringRedisTemplate Bean
     * 使用 String 序列化，方便除錯與跨語言溝通
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory connectionFactory
    ) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
```

---

### Step 1.3：確認 application.yml Redis 設定

**檢查檔案**：`src/main/resources/application.yml`

確認包含以下設定（或新增）：

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

### Step 1.4：驗收條件

- [ ] `RedisConfig.java` 正確建立
- [ ] `StringRedisTemplate` Bean 已註冊
- [ ] `application.yml` 包含 Redis 設定
- [ ] 執行 `./mvnw compile` 成功

---

## Phase 2：CacheService 核心服務

### Step 2.1：建立 CacheKeys.java — Key 常數定義

**檔案位置**：`src/main/java/com/pk/support_ticket_api/common/cache/CacheKeys.java`

**新建內容**：

```java
package com.pk.support_ticket_api.common.cache;

/**
 * 快取 Key 常數定義
 * 統一管理所有快取 Key，避免 key 命名衝突
 */
public final class CacheKeys {

    private CacheKeys() {}

    // ==================== Category ====================
    
    /** Active Categories 列表 */
    public static final String CATEGORY_ACTIVE_LIST = "category:active:list";

    // ==================== Ticket ====================
    
    /**
     * Ticket 詳情
     * @param ticketId Ticket ID
     * @return 快取 Key
     */
    public static String ticket(String ticketId) {
        return "ticket:" + ticketId;
    }

    // ==================== User ====================
    
    /**
     * User Profile
     * @param userId User ID
     * @return 快取 Key
     */
    public static String user(String userId) {
        return "user:" + userId;
    }

    // ==================== Dashboard ====================
    
    /** Dashboard 統計摘要 */
    public static final String DASHBOARD_SUMMARY = "dashboard:summary";
}
```

---

### Step 2.2：建立 CacheTtl.java — TTL 常數定義

**檔案位置**：`src/main/java/com/pk/support_ticket_api/common/cache/CacheTtl.java`

**新建內容**：

```java
package com.pk.support_ticket_api.common.cache;

import java.time.Duration;

/**
 * 快取 TTL（Time To Live）常數定義
 * 定義各類資料的快取存活時間
 */
public final class CacheTtl {

    private CacheTtl() {}

    /** Active Categories：30 分鐘（讀多寫少） */
    public static final Duration ACTIVE_CATEGORIES = Duration.ofMinutes(30);

    /** Ticket Detail：5 分鐘（使用者可能重複查看） */
    public static final Duration TICKET_DETAIL = Duration.ofMinutes(5);

    /** User Profile：10 分鐘（頻繁讀取） */
    public static final Duration USER_PROFILE = Duration.ofMinutes(10);

    /** Dashboard Summary：1 分鐘（統計查詢可接受短暫延遲） */
    public static final Duration DASHBOARD_SUMMARY = Duration.ofMinutes(1);
}
```

---

### Step 2.3：建立 CacheService.java — 介面定義

**檔案位置**：`src/main/java/com/pk/support_ticket_api/common/cache/CacheService.java`

**新建內容**：

```java
package com.pk.support_ticket_api.common.cache;

import org.springframework.core.ParameterizedTypeReference;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 快取服務介面
 * 定義快取操作的標準方法
 */
public interface CacheService {

    /**
     * 取得快取值，不存在時透過 loader 載入並快取
     *
     * @param key   快取 Key
     * @param type  目標類型
     * @param loader 資料載入器（當快取未命中時呼叫）
     * @param <T>   回傳類型
     * @return 快取值或載入的資料
     */
    <T> T get(String key, Class<T> type, Supplier<T> loader);

    /**
     * 取得快取值（含自訂 TTL），不存在時透過 loader 載入並快取
     *
     * @param key   快取 Key
     * @param type  目標類型
     * @param ttl   快取存活時間
     * @param loader 資料載入器
     * @param <T>   回傳類型
     * @return 快取值或載入的資料
     */
    <T> T get(String key, Class<T> type, Duration ttl, Supplier<T> loader);

    /**
     * 取得快取值（使用 ParameterizedTypeReference）
     */
    <T> T get(
            String key,
            ParameterizedTypeReference<T> type,
            Supplier<T> loader
    );

    /**
     * 取得快取值（含自訂 TTL，使用 ParameterizedTypeReference）
     */
    <T> T get(
            String key,
            ParameterizedTypeReference<T> type,
            Duration ttl,
            Supplier<T> loader
    );

    /**
     * 刪除單一快取 key
     *
     * @param key 快取 Key
     */
    void evict(String key);

    /**
     * 刪除符合 pattern 的所有 key
     *
     * @param pattern Redis key pattern（如 "ticket:*"）
     */
    void evictByPattern(String pattern);
}
```

---

### Step 2.4：建立 CacheServiceImpl.java — 實作

**檔案位置**：`src/main/java/com/pk/support_ticket_api/common/cache/CacheServiceImpl.java`

**新建內容**：

```java
package com.pk.support_ticket_api.common.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 快取服務實作
 * 採用 Cache-Aside 模式，先查快取，未命中再查 DB 並寫入快取
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheServiceImpl implements CacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private ValueOperations<String, String> getValueOps() {
        return redisTemplate.opsForValue();
    }

    // ==================== get() 實作 ====================

    @Override
    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        return get(key, type, null, loader);
    }

    @Override
    public <T> T get(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        try {
            String cached = getValueOps().get(key);
            
            if (cached != null) {
                log.debug("[CACHE] HIT: {}", key);
                return deserialize(cached, type);
            }
            
            log.debug("[CACHE] MISS: {}", key);
            T value = loader.get();
            
            if (value != null) {
                String serialized = serialize(value);
                if (ttl != null) {
                    getValueOps().set(key, serialized, ttl);
                } else {
                    getValueOps().set(key, serialized);
                }
            }
            
            return value;
        } catch (RedisConnectionFailureException e) {
            log.warn("[CACHE] Redis unavailable, falling back to DB: {}", e.getMessage());
            return loader.get();
        } catch (JsonProcessingException e) {
            log.warn("[CACHE] Deserialize failed for key {}, evicting: {}", key, e.getMessage());
            evict(key);
            return loader.get();
        }
    }

    @Override
    public <T> T get(String key, ParameterizedTypeReference<T> type, Supplier<T> loader) {
        return get(key, type, null, loader);
    }

    @Override
    public <T> T get(
            String key,
            ParameterizedTypeReference<T> type,
            Duration ttl,
            Supplier<T> loader
    ) {
        try {
            String cached = getValueOps().get(key);
            
            if (cached != null) {
                log.debug("[CACHE] HIT: {}", key);
                return deserialize(cached, type);
            }
            
            log.debug("[CACHE] MISS: {}", key);
            T value = loader.get();
            
            if (value != null) {
                String serialized = serialize(value);
                if (ttl != null) {
                    getValueOps().set(key, serialized, ttl);
                } else {
                    getValueOps().set(key, serialized);
                }
            }
            
            return value;
        } catch (RedisConnectionFailureException e) {
            log.warn("[CACHE] Redis unavailable, falling back to DB: {}", e.getMessage());
            return loader.get();
        } catch (JsonProcessingException e) {
            log.warn("[CACHE] Deserialize failed for key {}, evicting: {}", key, e.getMessage());
            evict(key);
            return loader.get();
        }
    }

    // ==================== evict() 實作 ====================

    @Override
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("[CACHE] EVICT: {}", key);
        } catch (RedisConnectionFailureException e) {
            log.warn("[CACHE] Failed to evict key {}, Redis may be unavailable: {}", key, e.getMessage());
        }
    }

    @Override
    public void evictByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("[CACHE] EVICT PATTERN: {} ({} keys)", pattern, keys.size());
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("[CACHE] Failed to evict pattern {}, Redis may be unavailable: {}", pattern, e.getMessage());
        }
    }

    // ==================== 序列化輔助方法 ====================

    private <T> String serialize(T value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private <T> T deserialize(String json, Class<T> type) throws JsonProcessingException {
        return objectMapper.readValue(json, type);
    }

    private <T> T deserialize(String json, ParameterizedTypeReference<T> type) 
            throws JsonProcessingException {
        return objectMapper.readValue(json, objectMapper.constructType(type.getType()));
    }
}
```

---

### Step 2.5：驗收條件

- [ ] `CacheKeys.java` 正確建立
- [ ] `CacheTtl.java` 正確建立
- [ ] `CacheService.java` 介面正確
- [ ] `CacheServiceImpl.java` 實作正確
- [ ] Redis 連線失敗時會降級為直接查 DB
- [ ] 執行 `./mvnw compile` 成功

---

## Phase 3：Category 快取整合

### Step 3.1：修改 CategoryServiceImpl.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/categories/service/CategoryServiceImpl.java`

**修改 1：注入 CacheService**

```java
@Service
@RequiredArgsConstructor
@Transactional
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final CacheService cacheService;  // 新增
```

**新增 import**：

```java
import com.pk.support_ticket_api.common.cache.CacheKeys;
import com.pk.support_ticket_api.common.cache.CacheService;
import com.pk.support_ticket_api.common.cache.CacheTtl;
```

**修改 2：修改 `getActiveCategories()` 方法，加入快取**

```java
@Override
@Transactional(readOnly = true)
public PageResponse<CategorySummaryResponse> getActiveCategories(Pageable pageable) {
    return cacheService.get(
        CacheKeys.CATEGORY_ACTIVE_LIST,
        new ParameterizedTypeReference<PageResponse<CategorySummaryResponse>>() {},
        CacheTtl.ACTIVE_CATEGORIES,
        () -> loadActiveCategories(pageable)
    );
}

/**
 * 載入 Active Categories（當快取未命中時呼叫）
 */
private PageResponse<CategorySummaryResponse> loadActiveCategories(Pageable pageable) {
    Page<Category> page = categoryRepository.findAll(
        CategorySpecification.withFilters(true, null),
        pageable
    );
    return PageResponse.from(page, CategorySummaryResponse::from);
}
```

**修改 3：修改 `createCategory()` 方法，加入快取失效**

```java
@Override
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
    
    // 失效快取
    cacheService.evict(CacheKeys.CATEGORY_ACTIVE_LIST);
    
    return CategoryResponse.from(saved);
}
```

**修改 4：修改 `updateCategory()` 方法，加入快取失效**

```java
@Override
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
    
    // 失效快取
    cacheService.evict(CacheKeys.CATEGORY_ACTIVE_LIST);
    
    return CategoryResponse.from(saved);
}
```

**修改 5：修改 `deactivateCategory()` 方法，加入快取失效**

```java
@Override
public CategoryResponse deactivateCategory(UUID id) {
    Category category = findCategoryById(id);

    if (!category.getActive()) {
        throw new BusinessRuleException("Category is already deactivated");
    }

    category.setActive(false);
    Category saved = categoryRepository.save(category);
    
    // 失效快取
    cacheService.evict(CacheKeys.CATEGORY_ACTIVE_LIST);
    
    return CategoryResponse.from(saved);
}
```

**修改 6：修改 `activateCategory()` 方法，加入快取失效**

```java
@Override
public CategoryResponse activateCategory(UUID id) {
    Category category = findCategoryById(id);

    if (category.getActive()) {
        throw new BusinessRuleException("Category is already active");
    }

    category.setActive(true);
    Category saved = categoryRepository.save(category);
    
    // 失效快取
    cacheService.evict(CacheKeys.CATEGORY_ACTIVE_LIST);
    
    return CategoryResponse.from(saved);
}
```

---

### Step 3.2：驗收條件

- [ ] `getActiveCategories()` 使用快取
- [ ] `createCategory()` 更新後失效快取
- [ ] `updateCategory()` 更新後失效快取
- [ ] `deactivateCategory()` 更新後失效快取
- [ ] `activateCategory()` 更新後失效快取
- [ ] 執行 `./mvnw compile` 成功

---

## Phase 4：User Profile 快取整合

### Step 4.1：修改 UserServiceImpl.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/users/service/UserServiceImpl.java`

**修改 1：注入 CacheService**

```java
@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final CacheService cacheService;  // 新增
```

**新增 import**：

```java
import com.pk.support_ticket_api.common.cache.CacheKeys;
import com.pk.support_ticket_api.common.cache.CacheService;
import com.pk.support_ticket_api.common.cache.CacheTtl;
```

**修改 2：修改 `findById()` 方法，加入快取**

```java
@Override
@Transactional(readOnly = true)
public UserResponse findById(UUID userId) {
    return cacheService.get(
        CacheKeys.user(userId.toString()),
        UserResponse.class,
        CacheTtl.USER_PROFILE,
        () -> loadUserById(userId)
    );
}

/**
 * 載入 User（當快取未命中時呼叫）
 */
private UserResponse loadUserById(UUID userId) {
    User user = findUserById(userId);
    return UserResponse.from(user);
}
```

**修改 3：修改 `updateUser()` 方法，加入快取失效**

```java
@Override
public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
    User user = findUserById(userId);

    if (request.email() != null && !request.email().equals(user.getEmail())) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email 已被使用");
        }
        user.setEmail(request.email());
    }

    if (request.displayName() != null) {
        user.setDisplayName(request.displayName());
    }

    if (request.role() != null) {
        user.setRole(parseRole(request.role()));
    }

    User saved = userRepository.save(user);
    
    // 失效快取
    cacheService.evict(CacheKeys.user(userId.toString()));
    
    return UserResponse.from(saved);
}
```

**修改 4：修改 `deactivate()` 方法，加入快取失效**

```java
@Override
public UserResponse deactivate(UUID userId) {
    User user = findUserById(userId);
    UUID currentUserId = getCurrentUserIdOrThrow();

    if (Objects.equals(user.getId(), currentUserId)) {
        throw new BusinessRuleException("不可停用自己的帳號");
    }

    if (isLastAdmin(user)) {
        throw new BusinessRuleException("不可停用最後一個 Admin 帳號");
    }

    user.setStatus(UserStatus.INACTIVE);
    User saved = userRepository.save(user);
    
    // 失效快取
    cacheService.evict(CacheKeys.user(userId.toString()));
    
    return UserResponse.from(saved);
}
```

**修改 5：修改 `activate()` 方法，加入快取失效**

```java
@Override
public UserResponse activate(UUID userId) {
    User user = findUserById(userId);
    user.setStatus(UserStatus.ACTIVE);
    User saved = userRepository.save(user);
    
    // 失效快取
    cacheService.evict(CacheKeys.user(userId.toString()));
    
    return UserResponse.from(saved);
}
```

---

### Step 4.2：驗收條件

- [ ] `findById()` 使用快取
- [ ] `updateUser()` 更新後失效快取
- [ ] `deactivate()` 更新後失效快取
- [ ] `activate()` 更新後失效快取
- [ ] 執行 `./mvnw compile` 成功

---

## Phase 5：Ticket Detail 快取整合

### Step 5.1：修改 TicketServiceImpl.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/tickets/service/TicketServiceImpl.java`

**修改 1：注入 CacheService**

```java
@Service
@RequiredArgsConstructor
@Transactional
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final AuditLogRepository auditLogRepository;
    private final TicketStateMachine stateMachine;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final SlaCalculator slaCalculator;
    private final Clock clock;
    private final CacheService cacheService;  // 新增
```

**新增 import**：

```java
import com.pk.support_ticket_api.common.cache.CacheKeys;
import com.pk.support_ticket_api.common.cache.CacheService;
import com.pk.support_ticket_api.common.cache.CacheTtl;
```

**修改 2：修改 `getTicketById()` 方法，加入快取**

```java
@Override
@Transactional(readOnly = true)
public TicketResponse getTicketById(UUID id, CurrentUser currentUser) {
    Ticket ticket = findTicketById(id);

    if (!hasReadPermission(ticket, currentUser)) {
        throw new ForbiddenOperationException("No permission to view this ticket");
    }

    return cacheService.get(
        CacheKeys.ticket(id.toString()),
        TicketResponse.class,
        CacheTtl.TICKET_DETAIL,
        () -> enrichResponse(ticket)
    );
}
```

**修改 3：修改 `createTicket()` 方法，加入快取失效**

在 `return enrichResponse(saved);` 之後加入快取失效：

```java
@Override
public TicketResponse createTicket(CreateTicketRequest request, UUID createdBy) {
    Category category = categoryRepository.findById(request.categoryId())
        .orElseThrow(() -> new ResourceNotFoundException(
            "Category not found: " + request.categoryId()));

    Instant createdAt = Instant.now(clock);
    TicketPriority priority = request.priority() != null
        ? request.priority()
        : TicketPriority.MEDIUM;
    Instant slaDeadline = slaCalculator.calculateSlaDueAt(category, priority, createdAt);

    Ticket ticket = new Ticket();
    ticket.setTitle(request.title());
    ticket.setDescription(request.description());
    ticket.setCategoryId(request.categoryId());
    ticket.setCreatedBy(createdBy);
    ticket.setPriority(priority);
    ticket.setSlaDeadline(slaDeadline);
    ticket.setStatus(TicketStatus.OPEN);

    Ticket saved = ticketRepository.save(ticket);

    // 記錄 Audit Log
    auditLogRepository.save(AuditLog.create(
        createdBy,
        saved.getId(),
        AuditAction.TICKET_CREATED
    ));

    TicketResponse response = enrichResponse(saved);
    
    // 寫入快取（新建的 Ticket 可能馬上被查看）
    cacheService.evict(CacheKeys.ticket(saved.getId().toString()));  // 清除舊的（如果有）
    
    return response;
}
```

**修改 4：修改 `updateTicket()` 方法，加入快取失效**

在 `return enrichResponse(saved);` 之後加入快取失效：

```java
@Override
public TicketResponse updateTicket(UUID id, UpdateTicketRequest request, CurrentUser currentUser) {
    Ticket ticket = findTicketById(id);

    if (stateMachine.isFinalState(ticket.getStatus())) {
        throw new ForbiddenOperationException("Cannot update closed ticket");
    }

    if (request.title() != null && !request.title().isBlank()) {
        ticket.setTitle(request.title());
    }

    if (request.description() != null) {
        ticket.setDescription(request.description());
    }

    if (request.categoryId() != null) {
        validateCategoryExists(request.categoryId());
        ticket.setCategoryId(request.categoryId());
    }

    if (request.priority() != null && request.priority() != ticket.getPriority()) {
        TicketPriority oldPriority = ticket.getPriority();
        ticket.setPriority(request.priority());
        recalculateSlaDeadline(ticket);

        // 記錄 Audit Log
        auditLogRepository.save(AuditLog.createFieldChange(
            currentUser.userId(),
            ticket.getId(),
            AuditAction.PRIORITY_CHANGED,
            AuditFieldName.PRIORITY,
            oldPriority.name(),
            request.priority().name()
        ));
    }

    Ticket saved = ticketRepository.save(ticket);
    
    // 失效快取
    cacheService.evict(CacheKeys.ticket(id.toString()));
    
    return enrichResponse(saved);
}
```

**修改 5：修改 `updateStatus()` 方法，加入快取失效**

在 `return enrichResponse(saved);` 之後加入快取失效：

```java
@Override
public TicketResponse updateStatus(UUID id, TicketStatusUpdateRequest request, CurrentUser currentUser) {
    Ticket ticket = findTicketById(id);

    if (!canChangeStatus(ticket, currentUser)) {
        throw new ForbiddenOperationException("No permission to change ticket status");
    }

    if (!stateMachine.canTransition(ticket.getStatus(), request.status())) {
        throw new ForbiddenOperationException(String.format(
            "Cannot transition from %s to %s",
            ticket.getStatus(),
            request.status()
        ));
    }

    TicketStatus oldStatus = ticket.getStatus();
    ticket.setStatus(request.status());
    handleStatusSideEffects(ticket, request.status());

    // 記錄 Audit Log
    AuditLog audit = AuditLog.createFieldChange(
        currentUser.userId(),
        ticket.getId(),
        AuditAction.STATUS_CHANGED,
        AuditFieldName.STATUS,
        oldStatus.name(),
        request.status().name()
    );
    auditLogRepository.save(audit);

    Ticket saved = ticketRepository.save(ticket);
    
    // 失效快取
    cacheService.evict(CacheKeys.ticket(id.toString()));
    
    return enrichResponse(saved);
}
```

**修改 6：修改 `assignTicket()` 方法，加入快取失效**

在 `return enrichResponse(saved);` 之後加入快取失效：

```java
@Override
public TicketResponse assignTicket(UUID id, TicketAssignRequest request, CurrentUser currentUser) {
    Ticket ticket = findTicketById(id);

    if (stateMachine.isFinalState(ticket.getStatus())) {
        throw new ForbiddenOperationException("Cannot assign closed ticket");
    }

    UUID assigneeUuid = null;
    if (request.assigneeId() != null && !request.assigneeId().isBlank()) {
        assigneeUuid = UUID.fromString(request.assigneeId());
        validateUserExists(assigneeUuid);
    }

    UUID oldAssigneeId = ticket.getAssignedTo();
    ticket.setAssignedTo(assigneeUuid);

    // 記錄 Audit Log
    if (assigneeUuid != null) {
        auditLogRepository.save(AuditLog.createFieldChange(
            currentUser.userId(),
            ticket.getId(),
            AuditAction.ASSIGNED,
            AuditFieldName.ASSIGNEE,
            oldAssigneeId != null ? oldAssigneeId.toString() : null,
            assigneeUuid.toString()
        ));
    } else if (oldAssigneeId != null) {
        auditLogRepository.save(AuditLog.create(
            currentUser.userId(),
            ticket.getId(),
            AuditAction.UNASSIGNED
        ));
    }

    Ticket saved = ticketRepository.save(ticket);
    
    // 失效快取
    cacheService.evict(CacheKeys.ticket(id.toString()));
    
    return enrichResponse(saved);
}
```

---

### Step 5.2：驗收條件

- [ ] `getTicketById()` 使用快取
- [ ] `createTicket()` 建立後失效快取
- [ ] `updateTicket()` 更新後失效快取
- [ ] `updateStatus()` 狀態變更後失效快取
- [ ] `assignTicket()` 指派後失效快取
- [ ] 執行 `./mvnw compile` 成功

---

## Phase 6：Dashboard Summary 快取

### Step 6.1：建立 DashboardService.java 介面

**檔案位置**：`src/main/java/com/pk/support_ticket_api/reporting/service/DashboardService.java`

**新建內容**：

```java
package com.pk.support_ticket_api.reporting.service;

import com.pk.support_ticket_api.reporting.dto.DashboardSummaryResponse;

import java.util.UUID;

/**
 * Dashboard 統計服務介面
 */
public interface DashboardService {

    /**
     * 取得 Dashboard 統計摘要
     * 結果會被快取，TTL 為 1 分鐘
     *
     * @param currentUserId 目前登入使用者的 ID（用於權限控制）
     * @return Dashboard 統計摘要
     */
    DashboardSummaryResponse getSummary(UUID currentUserId);
}
```

---

### Step 6.2：建立 DashboardSummaryResponse.java DTO

**檔案位置**：`src/main/java/com/pk/support_ticket_api/reporting/dto/DashboardSummaryResponse.java`

**新建內容**：

```java
package com.pk.support_ticket_api.reporting.dto;

import com.pk.support_ticket_api.common.domain.enums.TicketPriority;

import java.util.Map;

/**
 * Dashboard 統計摘要回應
 */
public record DashboardSummaryResponse(
    long openCount,               // Open 狀態工單數
    long inProgressCount,         // In Progress 狀態工單數
    long overdueCount,            // 逾期工單數
    long resolvedTodayCount,      // 今日已解決工單數
    Map<String, Long> byPriority, // 按優先級分組（key 為 Priority 名稱）
    Map<String, Long> byCategory  // 按分類分組（key 為 Category 名稱）
) {}
```

---

### Step 6.3：建立 DashboardServiceImpl.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/reporting/service/DashboardServiceImpl.java`

**新建內容**：

```java
package com.pk.support_ticket_api.reporting.service;

import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.common.cache.CacheKeys;
import com.pk.support_ticket_api.common.cache.CacheService;
import com.pk.support_ticket_api.common.cache.CacheTtl;
import com.pk.support_ticket_api.common.domain.enums.TicketPriority;
import com.pk.support_ticket_api.common.domain.enums.TicketStatus;
import com.pk.support_ticket_api.reporting.dto.DashboardSummaryResponse;
import com.pk.support_ticket_api.tickets.domain.Ticket;
import com.pk.support_ticket_api.tickets.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private final TicketRepository ticketRepository;
    private final CategoryRepository categoryRepository;
    private final CacheService cacheService;

    @Override
    public DashboardSummaryResponse getSummary(UUID currentUserId) {
        return cacheService.get(
            CacheKeys.DASHBOARD_SUMMARY,
            DashboardSummaryResponse.class,
            CacheTtl.DASHBOARD_SUMMARY,
            this::loadSummary
        );
    }

    /**
     * 載入 Dashboard 統計（當快取未命中時呼叫）
     * 計算 Open、In Progress、Overdue、Resolved Today 的數量
     * 以及按 Priority 和 Category 的分組統計
     */
    private DashboardSummaryResponse loadSummary() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant startOfToday = today.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfToday = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        // 取得所有 Tickets（可用 Specification 優化）
        var tickets = ticketRepository.findAll();

        long openCount = 0;
        long inProgressCount = 0;
        long overdueCount = 0;
        long resolvedTodayCount = 0;
        Map<String, Long> byPriority = new HashMap<>();
        Map<String, Long> byCategory = new HashMap<>();

        for (Ticket ticket : tickets) {
            // 狀態計數
            switch (ticket.getStatus()) {
                case OPEN -> openCount++;
                case IN_PROGRESS -> inProgressCount++;
                case RESOLVED -> {
                    if (ticket.getResolvedAt() != null &&
                        !ticket.getResolvedAt().isBefore(startOfToday) &&
                        ticket.getResolvedAt().isBefore(endOfToday)) {
                        resolvedTodayCount++;
                    }
                }
                default -> { /* CLOSED 不計入 */ }
            }

            // 逾期計數
            if (ticket.getStatus() != TicketStatus.RESOLVED &&
                ticket.getStatus() != TicketStatus.CLOSED &&
                ticket.getSlaDeadline() != null &&
                ticket.getSlaDeadline().isBefore(now)) {
                overdueCount++;
            }

            // Priority 分組
            String priorityName = ticket.getPriority().name();
            byPriority.merge(priorityName, 1L, Long::sum);

            // Category 分組
            String categoryName = categoryRepository.findById(ticket.getCategoryId())
                .map(c -> c.getName())
                .orElse("Unknown");
            byCategory.merge(categoryName, 1L, Long::sum);
        }

        return new DashboardSummaryResponse(
            openCount,
            inProgressCount,
            overdueCount,
            resolvedTodayCount,
            byPriority,
            byCategory
        );
    }
}
```

---

### Step 6.4：建立 DashboardController.java

**檔案位置**：`src/main/java/com/pk/support_ticket_api/reporting/web/DashboardController.java`

**新建內容**：

```java
package com.pk.support_ticket_api.reporting.web;

import com.pk.support_ticket_api.common.security.CurrentUser;
import com.pk.support_ticket_api.reporting.dto.DashboardSummaryResponse;
import com.pk.support_ticket_api.reporting.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "儀表板統計 API")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @Operation(summary = "取得 Dashboard 統計摘要")
    public ResponseEntity<DashboardSummaryResponse> getSummary(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        DashboardSummaryResponse response = dashboardService.getSummary(currentUser.userId());
        return ResponseEntity.ok(response);
    }
}
```

---

### Step 6.5：驗收條件

- [ ] `DashboardService.java` 介面正確
- [ ] `DashboardSummaryResponse.java` DTO 正確
- [ ] `DashboardServiceImpl.java` 實作正確，使用快取
- [ ] `DashboardController.java` 正確建立
- [ ] 執行 `./mvnw compile` 成功

---

## Phase 7：單元測試（必做）

### Step 7.1：建立 CacheServiceTest.java

**檔案位置**：`src/test/java/com/pk/support_ticket_api/common/cache/CacheServiceTest.java`

**新建內容**：

```java
package com.pk.support_ticket_api.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheService 單元測試")
class CacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheServiceImpl cacheService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new CacheServiceImpl(redisTemplate, objectMapper);
    }

    @Nested
    @DisplayName("get() 測試")
    class GetTests {

        @Test
        @DisplayName("快取命中時不回呼 loader")
        void shouldReturnCachedValue_whenCacheHit() {
            // Given
            String key = "test:key";
            String cachedValue = "\"cached\"";
            when(valueOperations.get(key)).thenReturn(cachedValue);

            // When
            String result = cacheService.get(key, String.class, () -> "loaded");

            // Then
            assertThat(result).isEqualTo("cached");
            verify(valueOperations, never()).set(any(), any());
        }

        @Test
        @DisplayName("快取未命中時呼叫 loader 並寫入快取")
        void shouldLoadAndCacheValue_whenCacheMiss() {
            // Given
            String key = "test:key";
            when(valueOperations.get(key)).thenReturn(null);

            // When
            String result = cacheService.get(key, String.class, () -> "loaded");

            // Then
            assertThat(result).isEqualTo("loaded");
            verify(valueOperations).set(eq(key), any(String.class));
        }

        @Test
        @DisplayName("快取未命中時使用指定的 TTL")
        void shouldSetTtl_whenCacheMiss() {
            // Given
            String key = "test:key";
            Duration ttl = Duration.ofMinutes(5);
            when(valueOperations.get(key)).thenReturn(null);

            // When
            cacheService.get(key, String.class, ttl, () -> "loaded");

            // Then
            verify(valueOperations).set(eq(key), any(String.class), eq(ttl));
        }

        @Test
        @DisplayName("loader 回傳 null 時不寫入快取")
        void shouldNotCacheNullValue() {
            // Given
            String key = "test:key";
            when(valueOperations.get(key)).thenReturn(null);

            // When
            String result = cacheService.get(key, String.class, () -> null);

            // Then
            assertThat(result).isNull();
            verify(valueOperations, never()).set(any(), any());
        }

        @Test
        @DisplayName("Redis 連線失敗時降級為直接呼叫 loader")
        void shouldFallbackToLoader_whenRedisUnavailable() {
            // Given
            String key = "test:key";
            when(valueOperations.get(key)).thenThrow(
                new RedisConnectionFailureException("Connection refused")
            );

            // When
            String result = cacheService.get(key, String.class, () -> "fallback");

            // Then
            assertThat(result).isEqualTo("fallback");
        }
    }

    @Nested
    @DisplayName("evict() 測試")
    class EvictTests {

        @Test
        @DisplayName("應正確刪除指定的 key")
        void shouldDeleteKey() {
            // Given
            String key = "test:key";

            // When
            cacheService.evict(key);

            // Then
            verify(redisTemplate).delete(key);
        }

        @Test
        @DisplayName("Redis 連線失敗時不拋例外")
        void shouldNotThrow_whenRedisUnavailable() {
            // Given
            String key = "test:key";
            doThrow(new RedisConnectionFailureException("Connection refused"))
                .when(redisTemplate).delete(key);

            // When & Then - 不拋例外
            cacheService.evict(key);
        }
    }

    @Nested
    @DisplayName("evictByPattern() 測試")
    class EvictByPatternTests {

        @Test
        @DisplayName("應正確刪除符合 pattern 的所有 key")
        void shouldDeleteKeysMatchingPattern() {
            // Given
            String pattern = "ticket:*";
            when(redisTemplate.keys(pattern)).thenReturn(
                java.util.Set.of("ticket:1", "ticket:2", "ticket:3")
            );

            // When
            cacheService.evictByPattern(pattern);

            // Then
            verify(redisTemplate).delete(anySet());
        }

        @Test
        @DisplayName("沒有符合的 key 時不執行刪除")
        void shouldNotDelete_whenNoMatchingKeys() {
            // Given
            String pattern = "ticket:*";
            when(redisTemplate.keys(pattern)).thenReturn(java.util.Set.of());

            // When
            cacheService.evictByPattern(pattern);

            // Then
            verify(redisTemplate, never()).delete(anyCollection());
        }
    }
}
```

---

### Step 7.2：建立 CategoryCacheTest.java

**檔案位置**：`src/test/java/com/pk/support_ticket_api/categories/service/CategoryServiceCacheTest.java`

**新建內容**：

```java
package com.pk.support_ticket_api.categories.service;

import com.pk.support_ticket_api.categories.domain.Category;
import com.pk.support_ticket_api.categories.dto.CategoryResponse;
import com.pk.support_ticket_api.categories.dto.CategorySummaryResponse;
import com.pk.support_ticket_api.categories.dto.CreateCategoryRequest;
import com.pk.support_ticket_api.categories.repository.CategoryRepository;
import com.pk.support_ticket_api.common.cache.CacheKeys;
import com.pk.support_ticket_api.common.cache.CacheService;
import com.pk.support_ticket_api.common.cache.CacheTtl;
import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryService 快取測試")
class CategoryServiceCacheTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    private Category testCategory;
    private UUID testId;

    @BeforeEach
    void setUp() {
        testId = UUID.randomUUID();
        testCategory = new Category();
        testCategory.setId(testId);
        testCategory.setName("技術問題");
        testCategory.setDescription("技術相關問題");
        testCategory.setSlaHoursLow(72);
        testCategory.setSlaHoursMedium(48);
        testCategory.setSlaHoursHigh(24);
        testCategory.setSlaHoursUrgent(4);
        testCategory.setActive(true);
    }

    @Nested
    @DisplayName("getActiveCategories() 快取測試")
    class GetActiveCategoriesCacheTests {

        @Test
        @DisplayName("應使用快取")
        void shouldUseCache() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            Page<Category> page = new PageImpl<>(List.of(testCategory));
            CategorySummaryResponse cachedResponse = CategorySummaryResponse.from(testCategory);

            when(cacheService.get(
                eq(CacheKeys.CATEGORY_ACTIVE_LIST),
                any(),
                eq(CacheTtl.ACTIVE_CATEGORIES),
                any()
            )).thenReturn(new com.pk.support_ticket_api.common.response.PageResponse<>(
                List.of(cachedResponse),
                0, 20, 1, 1, "createdAt,desc"
            ));

            // When
            var result = categoryService.getActiveCategories(pageable);

            // Then
            verify(cacheService).get(
                eq(CacheKeys.CATEGORY_ACTIVE_LIST),
                any(),
                eq(CacheTtl.ACTIVE_CATEGORIES),
                any()
            );
        }
    }

    @Nested
    @DisplayName("createCategory() 快取失效測試")
    class CreateCategoryCacheEvictionTests {

        @Test
        @DisplayName("建立後應失效快取")
        void shouldEvictCache_afterCreate() {
            // Given
            CreateCategoryRequest request = new CreateCategoryRequest(
                "新分類", "描述", 72, 48, 24, 4
            );

            when(categoryRepository.existsByName("新分類")).thenReturn(false);
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);

            // When
            categoryService.createCategory(request);

            // Then
            verify(cacheService).evict(CacheKeys.CATEGORY_ACTIVE_LIST);
        }
    }

    @Nested
    @DisplayName("updateCategory() 快取失效測試")
    class UpdateCategoryCacheEvictionTests {

        @Test
        @DisplayName("更新後應失效快取")
        void shouldEvictCache_afterUpdate() {
            // Given
            var request = new com.pk.support_ticket_api.categories.dto.UpdateCategoryRequest(
                "新名稱", "新描述", 96, 72, 48, 8
            );

            when(categoryRepository.findById(testId)).thenReturn(java.util.Optional.of(testCategory));
            when(categoryRepository.existsByNameAndIdNot("新名稱", testId)).thenReturn(false);
            when(categoryRepository.save(any(Category.class))).thenReturn(testCategory);

            // When
            categoryService.updateCategory(testId, request);

            // Then
            verify(cacheService).evict(CacheKeys.CATEGORY_ACTIVE_LIST);
        }
    }
}
```

---

### Step 7.3：執行測試

```bash
./mvnw test -Dtest=CacheServiceTest,CategoryServiceCacheTest
```

確認所有測試通過。

---

## 完整檔案清單

### 新增檔案（12 個）

```
src/main/java/com/pk/support_ticket_api/
├── common/
│   ├── config/
│   │   └── RedisConfig.java                    # Redis 設定
│   └── cache/
│       ├── CacheKeys.java                      # Key 常數定義
│       ├── CacheTtl.java                       # TTL 常數定義
│       ├── CacheService.java                   # 介面
│       └── CacheServiceImpl.java               # 實作
│
└── reporting/
    ├── dto/
    │   └── DashboardSummaryResponse.java       # Dashboard 統計 DTO
    ├── service/
    │   ├── DashboardService.java               # 介面
    │   └── DashboardServiceImpl.java           # 實作
    └── web/
        └── DashboardController.java            # Controller

src/test/java/com/pk/support_ticket_api/
└── common/
    └── cache/
        └── CacheServiceTest.java               # CacheService 單元測試
```

### 修改檔案（3 個）

```
src/main/java/com/pk/support_ticket_api/
├── categories/service/CategoryServiceImpl.java  # 整合快取
├── users/service/UserServiceImpl.java           # 整合快取
└── tickets/service/TicketServiceImpl.java       # 整合快取
```

**總計**：12 個新增檔案 + 3 個修改檔案

---

## 實作檢查清單

### Phase 1：Redis 基礎設定

- [ ] Step 1.1：確認 Redis 依賴存在
- [ ] Step 1.2：建立 `RedisConfig.java`
- [ ] Step 1.3：確認 `application.yml` Redis 設定
- [ ] Step 1.4：執行編譯

### Phase 2：CacheService 核心服務

- [ ] Step 2.1：建立 `CacheKeys.java`
- [ ] Step 2.2：建立 `CacheTtl.java`
- [ ] Step 2.3：建立 `CacheService.java` 介面
- [ ] Step 2.4：建立 `CacheServiceImpl.java` 實作
- [ ] Step 2.5：執行編譯

### Phase 3：Category 快取整合

- [ ] Step 3.1：修改 `CategoryServiceImpl.java`
  - [ ] 注入 CacheService
  - [ ] `getActiveCategories()` 使用快取
  - [ ] `createCategory()` 失效快取
  - [ ] `updateCategory()` 失效快取
  - [ ] `deactivateCategory()` 失效快取
  - [ ] `activateCategory()` 失效快取

### Phase 4：User Profile 快取整合

- [ ] Step 4.1：修改 `UserServiceImpl.java`
  - [ ] 注入 CacheService
  - [ ] `findById()` 使用快取
  - [ ] `updateUser()` 失效快取
  - [ ] `deactivate()` 失效快取
  - [ ] `activate()` 失效快取

### Phase 5：Ticket Detail 快取整合

- [ ] Step 5.1：修改 `TicketServiceImpl.java`
  - [ ] 注入 CacheService
  - [ ] `getTicketById()` 使用快取
  - [ ] `createTicket()` 失效快取
  - [ ] `updateTicket()` 失效快取
  - [ ] `updateStatus()` 失效快取
  - [ ] `assignTicket()` 失效快取

### Phase 6：Dashboard Summary 快取

- [ ] Step 6.1：建立 `DashboardService.java` 介面
- [ ] Step 6.2：建立 `DashboardSummaryResponse.java` DTO
- [ ] Step 6.3：建立 `DashboardServiceImpl.java` 實作
- [ ] Step 6.4：建立 `DashboardController.java`
- [ ] Step 6.5：執行編譯

### Phase 7：單元測試

- [ ] Step 7.1：建立 `CacheServiceTest.java`
- [ ] Step 7.2：建立 `CategoryServiceCacheTest.java`
- [ ] Step 7.3：執行測試

---

## 驗證步驟

### Step 1：執行編譯

```bash
./mvnw compile
```

確認所有程式碼編譯通過。

### Step 2：執行測試

```bash
./mvnw test
```

確認所有測試通過。

### Step 3：啟動應用程式

```bash
./mvnw spring-boot:run
```

確認 Redis 連線正常。

### Step 4：驗證快取功能

使用 Swagger UI 或 Postman：

1. **GET /api/categories** → 第一次呼叫（快取未命中）
2. **GET /api/categories** → 第二次呼叫（快取命中，日誌顯示 `[CACHE] HIT`）
3. **POST /api/admin/categories** → 建立新分類
4. **GET /api/categories** → 快取已失效，重新載入

### Step 5：檢查日誌

確認日誌包含以下內容：

```
[CACHE] HIT: category:active:list
[CACHE] MISS: ticket:xxx
[CACHE] EVICT: category:active:list
```

---

## 風險與注意事項

| 風險 | 說明 | 緩解措施 |
|------|------|----------|
| Redis 不可用 | 系統仍需正常運作 | 已實作降級機制 |
| 快取不一致 | 寫入後快取未失效 | 已在各寫入方法中加入 evict |
| TTL 設定不合理 | 資料過期但仍在使用 | 先用保守值，後續依監控調整 |

---

*實作步驟版本：v1.0 | 2026-09-17*
