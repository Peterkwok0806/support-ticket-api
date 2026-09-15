package com.pk.support_ticket_api.common.config;

import com.pk.support_ticket_api.common.domain.enums.Role;
import com.pk.support_ticket_api.users.domain.User;
import com.pk.support_ticket_api.users.domain.UserStatus;
import com.pk.support_ticket_api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/**
 * 初始化測試資料。
 *
 * 在應用程式啟動時建立預設的 demo 帳號，
 * 密碼由系統使用 PasswordEncoder 雜湊，確保與生產環境一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String AGENT_EMAIL = "agent@example.com";
    private static final String CUSTOMER_EMAIL = "customer@example.com";

    private static final String ADMIN_PASSWORD = "admin123";
    private static final String AGENT_PASSWORD = "agent123";
    private static final String CUSTOMER_PASSWORD = "customer123";

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
    createUserIfNotExists(ADMIN_EMAIL, ADMIN_PASSWORD, "System Admin", Role.ADMIN);
    createUserIfNotExists(AGENT_EMAIL, AGENT_PASSWORD, "Support Agent", Role.AGENT);
    createUserIfNotExists(CUSTOMER_EMAIL, CUSTOMER_PASSWORD, "Happy Customer", Role.CUSTOMER);
    }

    private void createUserIfNotExists(String email, String rawPassword, String displayName, Role role) {
    // 改用 Email 檢查
    if (userRepository.existsByEmail(email)) {
        log.debug("User {} already exists, skipping", email);
        return;
    }

    User user = new User();
    // 註解掉或刪除這行！不要手動塞 ID
    // user.setId(id); 
    
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(rawPassword));
    user.setDisplayName(displayName);
    user.setRole(role);
    user.setStatus(UserStatus.ACTIVE);

    userRepository.save(user);
    log.info("Created demo user: {} with role {}", email, role);
    }
}
