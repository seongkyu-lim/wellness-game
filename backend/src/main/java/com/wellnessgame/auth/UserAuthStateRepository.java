package com.wellnessgame.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface UserAuthStateRepository extends JpaRepository<UserAuthState, String> {
    /**
     * 행이 없으면 만든다. 같은 사용자의 첫 기록이 동시에 와도 PK 위반(23505) 없이 한쪽은 아무것도 하지 않는다
     * (PostgreSQL 은 위반 시 트랜잭션 전체가 aborted 되므로 예외를 잡아 계속할 수 없다). 뒤 요청은 앞 트랜잭션이 끝날 때까지 기다린다.
     *
     * <p>{@code on conflict (user_id) do update} 는 H2(PostgreSQL 모드)가 지원하지 않아, 두 DB 가 모두 지원하는
     * 대상 없는 {@code on conflict do nothing} 과 {@link #advanceTo} 를 이어 쓴다. 이 테이블의 유일 제약은 PK 뿐이다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into user_auth_state (user_id, tokens_valid_after) values (:userId, :at) on conflict do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId, @Param("at") Instant at);

    /** 기준을 앞으로만 옮긴다(행 잠금을 잡는 단일 UPDATE 라 동시 요청에도 더 늦은 값이 남는다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update user_auth_state set tokens_valid_after = greatest(tokens_valid_after, :at) where user_id = :userId",
            nativeQuery = true)
    int advanceTo(@Param("userId") String userId, @Param("at") Instant at);
}
