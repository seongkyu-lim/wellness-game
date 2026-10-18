package com.wellnessgame.character;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserCharacterRepository extends JpaRepository<UserCharacter, UUID> {
    Optional<UserCharacter> findByUserId(String userId);

    /**
     * 동기화 중 같은 사용자의 동시 요청을 직렬화하기 위해 캐릭터 행을 쓰기 잠금으로 조회한다.
     * 트랜잭션 안에서만 호출해야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from UserCharacter c where c.userId = :userId")
    Optional<UserCharacter> findByUserIdForUpdate(@Param("userId") String userId);
}
