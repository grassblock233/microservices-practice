package com.zjgsu.lht;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class StockApiTests {
    @Autowired MockMvc mvc;
    @Test void rejectsMalformedAndInvalidBodies() throws Exception {
        for (String body:new String[]{"{", "{}", "{\"merchantId\":10,\"items\":[]}",
            "{\"merchantId\":10,\"items\":[{\"dishId\":1,\"quantity\":1.2}]}"})
            mvc.perform(post("/internal/dishes/validate").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    @Test void healthChecksOwnDatabase() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
