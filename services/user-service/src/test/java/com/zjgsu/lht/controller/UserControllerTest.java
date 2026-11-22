package com.zjgsu.lht.controller;

import com.zjgsu.lht.model.User;
import com.zjgsu.lht.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "JWT_SECRET=" + AuthControllerTest.TEST_SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserControllerTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    @ParameterizedTest
    @CsvSource({"student-1, ACTIVE", "student-disabled, DISABLED"})
    void returnsUserStatusForOrderServiceToValidate(String id, String userStatus) throws Exception {
        var user = new User();
        user.id = id;
        user.displayName = "测试用户";
        user.status = User.Status.valueOf(userStatus);
        users.save(user);
        mvc.perform(get("/api/users/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value(userStatus));
    }

    @Test
    void missingUserIsExplicit404() throws Exception {
        mvc.perform(get("/api/users/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
