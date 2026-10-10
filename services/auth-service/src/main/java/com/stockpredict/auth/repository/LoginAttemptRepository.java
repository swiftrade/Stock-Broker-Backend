package com.stockpredict.auth.repository;

import com.stockpredict.auth.domain.LoginAttemptEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface LoginAttemptRepository extends JpaRepository<LoginAttemptEntity, Long> {

    long countByEmailIgnoreCaseAndSuccessFalseAndAttemptedAtAfter(String email, Instant after);

    @Transactional
    int deleteByAttemptedAtBefore(Instant cutoff);
}
