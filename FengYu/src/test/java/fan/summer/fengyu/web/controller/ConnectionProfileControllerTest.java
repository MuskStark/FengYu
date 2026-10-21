package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.connections.ConnectionProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConnectionProfileControllerTest {

    private ConnectionProfileService connections;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        connections = mock(ConnectionProfileService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ConnectionProfileController(connections)).build();
    }

    private static ConnectionProfileService.ConnectionProfile profile(String id, String name, String type) {
        return new ConnectionProfileService.ConnectionProfile(id, name, type, null,
                Map.of("token", "abc"), Instant.now().toString(), Instant.now().toString());
    }

    @Test
    void listSupportsTheTypeFilterForDropdowns() throws Exception {
        when(connections.list("email")).thenReturn(List.of(profile("c-1", "Company mailbox", "email")));
        mvc.perform(get("/api/connections").param("type", "email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("c-1"))
                .andExpect(jsonPath("$[0].fields.token").value("abc"));
    }

    @Test
    void createReturns201WithTheStoredProfile() throws Exception {
        when(connections.create(any())).thenReturn(profile("c-2", "API key", "api-key"));
        mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"API key\",\"type\":\"api-key\",\"fields\":{\"token\":\"abc\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("c-2"));
    }

    @Test
    void updateAndDeleteRoundOutTheCrud() throws Exception {
        when(connections.update(eq("c-2"), any())).thenReturn(profile("c-2", "Renamed", "api-key"));
        mvc.perform(put("/api/connections/c-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"type\":\"api-key\",\"fields\":{\"token\":\"abc\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));

        when(connections.delete("c-2")).thenReturn(true);
        mvc.perform(delete("/api/connections/c-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
