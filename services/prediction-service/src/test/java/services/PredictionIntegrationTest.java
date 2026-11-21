package services;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PredictionIntegrationTest {
    private static final String ID = "507f1f77bcf86cd799439011";
    // Controlled fixtures, not measured model outputs.
    private static final String MODEL = "{\"prediction\":0,\"probabilities\":[0.8,0.2]}";
    private WireMockServer server;
    private PredictionRuntime runtime;
    private CkdPredictionService service;
    private PredictionSettings settings;

    @BeforeEach void start() {
        server = new WireMockServer(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1")); server.start();
        settings = new PredictionSettings();
        settings.setTimeoutMillis(500);
        settings.setBackoffMillis(10);
        settings.setAttempts(2);
        settings.setCircuitMinimumCalls(4);
        runtime = new PredictionRuntime(settings);
        var appointments = new AppointmentClient(new RestClientConfiguration().restClientBuilder(settings), server.baseUrl() + "/appointments", runtime);
        var reports = new ReportClient(new RestClientConfiguration().restClientBuilder(settings), server.baseUrl() + "/reports", runtime);
        var models = new CkdModelClient(server.baseUrl() + "/tab", server.baseUrl() + "/image", runtime);
        service = new CkdPredictionService(appointments, reports, models, runtime);
        caller();
        server.stubFor(get(urlEqualTo("/appointments/" + ID)).willReturn(okJson("""
                {"data":{"doctor_email":"assigned@example.invalid","patient_email":"p@example.invalid",
                "clinical_data":{"age":50},"ultrasound_data":["https://res.cloudinary.com/demo/image/upload/a.png","https://res.cloudinary.com/demo/image/upload/b.png"]}}
                """)));
        server.stubFor(get(urlEqualTo("/reports/" + ID)).willReturn(aResponse().withStatus(404)));
        server.stubFor(post(urlEqualTo("/tab")).willReturn(okJson(MODEL)));
        server.stubFor(post(urlEqualTo("/image")).willReturn(okJson(MODEL)));
        server.stubFor(post(urlEqualTo("/reports")).willReturn(created().withHeader("Content-Type", "application/json").withBody("{\"report\":{\"appointment_id\":\"" + ID + "\"}}")));
    }
    private static void caller() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("assigned@example.invalid", "caller-token",
                List.of(new SimpleGrantedAuthority("ROLE_Doctor"))));
    }
    @AfterEach void stop() { SecurityContextHolder.clearContext(); runtime.close(); server.stop(); }
    private CkdPredictionService.PredictionResult predict() { return service.predict(Map.of("appointment_id", ID)); }

    @Test void recordsStageTimersAndPropagatesCorrelationAcrossAllHttpCalls() {
        org.slf4j.MDC.put("requestId", "prediction-observability-test");
        try {
            assertEquals(200, predict().status());
            assertEquals(1, runtime.meters().get("ckd.prediction.total").tag("outcome", "success").timer().count());
            assertEquals(1, runtime.meters().get("ckd.appointment.fetch").timer().count());
            assertEquals(1, runtime.meters().get("ckd.model.call").tag("operation", "tabular").timer().count());
            assertEquals(2, runtime.meters().get("ckd.model.call").tag("operation", "image").timer().count());
            assertEquals(1, runtime.meters().get("ckd.report.store").tag("operation", "create").timer().count());
            for (var event : server.getAllServeEvents()) {
                assertEquals("prediction-observability-test", event.getRequest().getHeader("X-Request-Id"));
            }
        } finally { org.slf4j.MDC.clear(); }
    }
    @Test void failedCallsAreTimedWithoutPatientOrRequestIdTags() {
        server.stubFor(post("/tab").willReturn(serverError()));
        assertEquals(502, predict().status());
        assertEquals(1, runtime.meters().get("ckd.model.call").tag("operation", "tabular").tag("outcome", "error").timer().count());
        assertEquals(1, runtime.meters().get("ckd.prediction.total").tag("outcome", "error").timer().count());
        for (var meter : runtime.meters().getMeters()) {
            assertTrue(meter.getId().getTags().stream().allMatch(tag -> java.util.Set.of("operation", "outcome").contains(tag.getKey())));
        }
    }
    @Test void mlTimeoutIs503AndRetried() {
        server.stubFor(post("/tab").willReturn(okJson(MODEL).withFixedDelay(2000)));
        assertEquals(503, predict().status());
        server.verify(2, postRequestedFor(urlEqualTo("/tab")));
        server.verify(0, postRequestedFor(urlEqualTo("/reports")));
    }
    @Test void ml500Is502AndRetried() {
        server.stubFor(post("/tab").willReturn(serverError()));
        assertEquals(502, predict().status());
        server.verify(2, postRequestedFor(urlEqualTo("/tab")));
    }
    @Test void malformedJsonIs502WithoutRetry() {
        server.stubFor(post("/tab").willReturn(okJson("{")));
        assertEquals(502, predict().status());
        server.verify(1, postRequestedFor(urlEqualTo("/tab")));
    }
    @Test void partialImageFailureDoesNotStoreReport() {
        server.stubFor(post("/image").withRequestBody(containing("b.png")).willReturn(serverError()));
        assertEquals(502, predict().status());
        server.verify(1, postRequestedFor(urlEqualTo("/image")).withRequestBody(containing("a.png")));
        server.verify(0, postRequestedFor(urlEqualTo("/reports")));
    }
    @Test void circuitOpenIs503WithoutAnotherHttpCall() {
        server.stubFor(post("/tab").willReturn(serverError()));
        assertEquals(502, predict().status());
        assertEquals(502, predict().status());
        assertEquals(503, predict().status());
        server.verify(4, postRequestedFor(urlEqualTo("/tab")));
    }
    @Test void existingReportSkipsModelsAndForceUpdatesIt() throws Exception {
        server.stubFor(get("/reports/" + ID).willReturn(okJson("{\"report\":{\"appointment_id\":\"" + ID + "\",\"results\":{\"saved\":true}}}")));
        assertEquals(200, predict().status());
        server.verify(0, postRequestedFor(urlEqualTo("/tab")));
        server.stubFor(patch("/reports/" + ID).willReturn(okJson("{\"report\":{\"appointment_id\":\"" + ID + "\"}}")));
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new Controllers.PredictionController(service)).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/predictions")
                        .param("force", "true").contentType("application/json").content("{\"appointment_id\":\"" + ID + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        server.verify(1, patchRequestedFor(urlEqualTo("/reports/" + ID)));
        server.verify(0, postRequestedFor(urlEqualTo("/reports")));
    }
    @Test void duplicateRequestsReturnOneStoredReport() throws Exception {
        server.stubFor(get("/reports/" + ID).inScenario("saved").whenScenarioStateIs("Started").willReturn(notFound()));
        server.stubFor(post("/reports").inScenario("saved").whenScenarioStateIs("Started").willSetStateTo("stored")
                .willReturn(created().withHeader("Content-Type", "application/json").withBody("{\"report\":{\"appointment_id\":\"" + ID + "\"}}")));
        server.stubFor(get("/reports/" + ID).inScenario("saved").whenScenarioStateIs("stored")
                .willReturn(okJson("{\"report\":{\"appointment_id\":\"" + ID + "\"}}")));
        try (var requests = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> { caller(); try { return predict().status(); } finally { SecurityContextHolder.clearContext(); } };
            var results = requests.invokeAll(List.of(task, task));
            for (var result : results) assertEquals(200, result.get());
        }
        server.verify(1, postRequestedFor(urlEqualTo("/reports")));
        server.verify(1, postRequestedFor(urlEqualTo("/tab")));
    }
    @Test void defaultFusionAndCallerTokenArePreserved() {
        var result = predict();
        assertEquals(200, result.status());
        server.verify(1, postRequestedFor(urlEqualTo("/reports"))
                .withRequestBody(matchingJsonPath("$.results.threshold", equalTo("0.5")))
                .withRequestBody(matchingJsonPath("$.results.fusion.method", equalTo("weighted_mean")))
                .withRequestBody(matchingJsonPath("$.results.modality_probabilities.tabular", equalTo("0.2"))));
        server.verify(2, postRequestedFor(urlEqualTo("/image")).withHeader("Authorization", equalTo("Bearer caller-token")));
    }
    @Test void unsafeUltrasoundUrlIs400BeforeAnyModelCall() {
        server.stubFor(get("/appointments/" + ID).willReturn(okJson("""
                {"data":{"doctor_email":"assigned@example.invalid","clinical_data":{"age":50},
                "ultrasound_data":["http://127.0.0.1/private"]}}
                """)));
        assertEquals(400, predict().status());
        server.verify(0, postRequestedFor(urlEqualTo("/tab")));
    }
    @Test void configuredWeightsAndThresholdAreUsed() {
        settings.setTabularWeight(3); settings.setImageWeight(1); settings.setThreshold(0.7);
        server.stubFor(post("/tab").willReturn(okJson("{\"prediction\":1,\"probabilities\":[0.2,0.8]}")));
        assertEquals(200, predict().status());
        server.verify(1, postRequestedFor(urlEqualTo("/reports"))
                .withRequestBody(matchingJsonPath("$.results.final_diagnosis", equalTo("No CKD")))
                .withRequestBody(matchingJsonPath("$.results.fusion.weights.tabular", equalTo("0.75"))));
        double probability = com.jayway.jsonpath.JsonPath.read(server.findAll(postRequestedFor(urlEqualTo("/reports"))).getFirst()
                .getBodyAsString(), "$.results.combined_probability");
        assertEquals(0.65, probability, 1e-12);
    }
    @Test void insertConflictFetchesWinningReportWithoutRetryingCreate() {
        server.stubFor(get("/reports/" + ID).inScenario("race").whenScenarioStateIs("Started")
                .willSetStateTo("winner").willReturn(notFound()));
        server.stubFor(get("/reports/" + ID).inScenario("race").whenScenarioStateIs("winner")
                .willReturn(okJson("{\"report\":{\"appointment_id\":\"" + ID + "\",\"winner\":true}}")));
        server.stubFor(post("/reports").willReturn(aResponse().withStatus(409)));
        var result = predict();
        assertEquals(200, result.status());
        assertTrue(result.body().toString().contains("winner=true"));
        server.verify(1, postRequestedFor(urlEqualTo("/reports")));
    }
    @Test void appointment503IsRetriedAndMappedTo503() {
        server.stubFor(get("/appointments/" + ID).willReturn(serviceUnavailable()));
        assertEquals(503, predict().status());
        server.verify(2, getRequestedFor(urlEqualTo("/appointments/" + ID)));
        server.verify(0, postRequestedFor(urlEqualTo("/tab")));
    }
    @Test void reportLookupOutageIsNotMistakenForMissingReport() {
        server.stubFor(get("/reports/" + ID).willReturn(serverError()));
        assertEquals(502, predict().status());
        server.verify(2, getRequestedFor(urlEqualTo("/reports/" + ID)));
        server.verify(0, postRequestedFor(urlEqualTo("/tab")));
    }
    @Test void upstream403IsNotRetried() {
        server.stubFor(get("/appointments/" + ID).willReturn(forbidden()));
        assertEquals(403, predict().status());
        server.verify(1, getRequestedFor(urlEqualTo("/appointments/" + ID)));
    }
    @Test void report503IsNotRetriedByTheHttpTransport() {
        server.stubFor(post("/reports").willReturn(serviceUnavailable()));
        assertEquals(503, predict().status());
        server.verify(1, postRequestedFor(urlEqualTo("/reports")));
    }
    @Test void reportCreateIsNeverRetried() {
        server.stubFor(post("/reports").willReturn(serverError()));
        assertEquals(502, predict().status());
        server.verify(1, postRequestedFor(urlEqualTo("/reports")));
    }
}
