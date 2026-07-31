package com.wellnessgame.api;

import com.wellnessgame.character.UserCharacter;
import com.wellnessgame.character.UserCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CharacterControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserCharacterRepository characterRepository;

    @BeforeEach
    void cleanDatabase() {
        characterRepository.deleteAll();
    }

    @Test
    void returnsCharacterForExistingUser() throws Exception {
        UserCharacter character = new UserCharacter("guest:web-user");
        character.addXp(150);
        characterRepository.save(character);

        mockMvc.perform(get("/api/characters/{userId}", "guest:web-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is("guest:web-user")))
                .andExpect(jsonPath("$.character.level", is(2)))
                .andExpect(jsonPath("$.character.currentXp", is(50)))
                .andExpect(jsonPath("$.character.totalXp", is(150)))
                .andExpect(jsonPath("$.character.nextLevelXp", is(150)))
                .andExpect(jsonPath("$.character.stats.str", is(0)));
    }

    @Test
    void returnsNotFoundForUnknownUser() throws Exception {
        mockMvc.perform(get("/api/characters/{userId}", "guest:nobody"))
                .andExpect(status().isNotFound());
    }

    @Test
    void allowsCorsRequestFromWebDevOrigin() throws Exception {
        mockMvc.perform(options("/api/characters/guest:web-user")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
