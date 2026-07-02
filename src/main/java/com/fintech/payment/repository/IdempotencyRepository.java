package com.fintech.payment.repository;

import com.fintech.payment.model.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link IdempotencyRecord}. The {@code String} type parameter
 * reflects that {@code idempotencyKey} is itself the primary key — no surrogate
 * id needed for retrieval.
 */
@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {
}
