package lv.ray.springb.repository;

import lv.ray.springb.entity.IdempotencyRecord;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;


@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long>
{
	Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);
}
