package org.example.musictraceback;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口返回测试:验证 /api/health 的响应结构(前端依赖该契约)
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_returnsExpectedStructure() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.app").value("MusicTraceBack"))
                .andExpect(jsonPath("$.database").value(not(emptyString())))
                .andExpect(jsonPath("$.time").value(not(emptyString())));
    }
}
