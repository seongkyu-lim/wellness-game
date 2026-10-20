package com.wellnessgame.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 토큰이 속한 family. 엔티티를 읽지 않아 뒤이은 잠금 조회({@link #lockFamily})가 최신 상태를 가져온다. */
    @Query("select t.familyId from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<UUID> findFamilyIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * family 의 모든 행을 잠근다(SELECT ... FOR UPDATE, id 순서로 잠가 교착을 피함). 회전과 로그아웃이 모두 이 잠금을 먼저 잡아
     * family 단위로 직렬화된다: 진행 중인 회전이 넣은 후계 토큰을 로그아웃이 놓치지 않고, 같은 토큰의 동시 회전은 앞선 회전이
     * 커밋한 최신 상태(revoked_at·replaced_by·grace_reissued)를 본다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.familyId = :familyId order by t.id")
    List<RefreshToken> lockFamily(@Param("familyId") UUID familyId);

    List<RefreshToken> findByFamilyId(UUID familyId);

    boolean existsByFamilyIdAndRevokedAtIsNullAndExpiresAtAfter(UUID familyId, Instant now);

    List<RefreshToken> findByUserId(String userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") String userId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt <= :now or t.revokedAt <= :revokedBefore")
    int deleteExpiredOrRevokedBefore(@Param("now") Instant now, @Param("revokedBefore") Instant revokedBefore);
}
