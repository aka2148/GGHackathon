package com.cybersecuals.gridgarrison.simulator;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvSimulatorController.class)
class EvSimulatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EvWebSocketClient client;

    @MockBean
    private EvTelemetryProfileProperties telemetryProfiles;

    @MockBean
    private EvSimulationScenarios scenarios;

    @Test
    void statusIncludesStationIdAndActiveProfile() throws Exception {
        when(client.getStationId()).thenReturn("EV-Simulator-001");
        when(client.isConnected()).thenReturn(true);
        when(telemetryProfiles.getActiveProfile()).thenReturn("normal");
        when(scenarios.isScenarioRunning()).thenReturn(false);

        mockMvc.perform(get("/api/ev/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stationId").value("EV-Simulator-001"))
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.activeProfile").value("normal"));
    }

    @Test
    void runScenarioReturnsBadRequestForUnknownScenario() throws Exception {
        when(scenarios.runScenarioAsync("unknownScenario")).thenReturn(false);
        when(scenarios.isScenarioRunning()).thenReturn(false);
        when(scenarios.getCurrentScenarioName()).thenReturn(null);

        mockMvc.perform(post("/api/ev/scenario/run").queryParam("name", "unknownScenario"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void firmwareStatusForwardsStatusAndHashToClient() throws Exception {
        Mockito.doNothing().when(client).sendFirmwareStatus("Downloaded", "abc123");

        mockMvc.perform(post("/api/ev/firmware/status")
                .queryParam("status", "Downloaded")
                .queryParam("hash", "abc123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.status").value("Downloaded"))
            .andExpect(jsonPath("$.hash").value("abc123"));

        verify(client).sendFirmwareStatus(eq("Downloaded"), eq("abc123"));
    }
}
