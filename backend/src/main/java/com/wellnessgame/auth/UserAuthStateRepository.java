package com.wellnessgame.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuthStateRepository extends JpaRepository<UserAuthState, String> {
}
