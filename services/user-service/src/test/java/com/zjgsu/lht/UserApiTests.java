package com.zjgsu.lht;
import com.zjgsu.lht.model.User;
import com.zjgsu.lht.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class UserApiTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @BeforeEach void setup() {
        users.deleteAll();
        var active=new User();active.id="student-1";active.displayName="同学一";active.status=User.Status.ACTIVE;users.save(active);
        var disabled=new User();disabled.id="student-disabled";disabled.displayName="禁用用户";disabled.status=User.Status.DISABLED;users.save(disabled);
    }
    @Test void returnsActiveUser() throws Exception {
        mvc.perform(get("/api/users/student-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("student-1")).andExpect(jsonPath("$.status").value("ACTIVE"));
    }
    @Test void returnsDisabledStatusForOrderServiceToReject() throws Exception {
        mvc.perform(get("/api/users/student-disabled")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
    }
    @Test void missingUserIsExplicit404() throws Exception {
        mvc.perform(get("/api/users/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
    @Test void healthChecksOwnDatabase() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
