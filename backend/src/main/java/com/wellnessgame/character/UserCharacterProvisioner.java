package com.wellnessgame.character;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 처음 보는 사용자의 캐릭터 행을 만든다.
 *
 * <p>같은 신규 사용자의 동기화가 동시에 오면 두 요청 모두 생성을 시도해 {@code uk_character_user} 위반이 날 수 있다.
 * 생성을 호출자 트랜잭션과 분리된 새 트랜잭션({@code REQUIRES_NEW})에서 즉시 커밋하고, 제약 위반은 "다른 요청이 먼저
 * 만들었다"는 뜻이므로 무시한다. 호출자는 그 뒤 {@link UserCharacterRepository#findByUserIdForUpdate} 로 잠가 쓴다.
 *
 * <p>호출자 트랜잭션에서 잠금 조회보다 <b>먼저</b> 불러야 한다. MySQL(REPEATABLE READ)에서 없는 행을 {@code FOR UPDATE}
 * 로 조회하면 갭 잠금이 잡혀, 다른 커넥션의 같은 키 삽입(여기의 새 트랜잭션)이 호출자 자신을 기다리게 된다.
 */
@Component
public class UserCharacterProvisioner {
    private static final Logger log = LoggerFactory.getLogger(UserCharacterProvisioner.class);

    private final UserCharacterRepository characterRepository;
    private final TransactionTemplate requiresNew;

    public UserCharacterProvisioner(UserCharacterRepository characterRepository, PlatformTransactionManager transactionManager) {
        this.characterRepository = characterRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 캐릭터가 없으면 만들어 커밋한다. 이미 있거나 동시 요청이 먼저 만들었으면 아무것도 하지 않는다. */
    public void createIfAbsent(String userId) {
        try {
            requiresNew.executeWithoutResult(status -> {
                if (characterRepository.findByUserId(userId).isEmpty()) {
                    characterRepository.saveAndFlush(new UserCharacter(userId));
                }
            });
        } catch (DataIntegrityViolationException alreadyCreated) {
            log.debug("동시 요청이 캐릭터를 먼저 생성함: userId={}", userId);
        }
    }
}
