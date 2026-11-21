package services;

import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PredictionFusionTest {
    private static final String ID = "507f1f77bcf86cd799439011";
    private static final String FIRST = "https://res.cloudinary.com/demo/a.png";
    private static final String SECOND = "https://res.cloudinary.com/demo/b.png";
    private AppointmentClient appointments;
    private ReportClient reports;
    private CkdModelClient models;
    private PredictionSettings settings;
    private PredictionRuntime runtime;
    private CkdPredictionService service;
    @BeforeEach void setUp() {
        appointments = mock(AppointmentClient.class); reports = mock(ReportClient.class); models = mock(CkdModelClient.class);
        settings = new PredictionSettings(); runtime = new PredictionRuntime(settings);
        service = new CkdPredictionService(appointments, reports, models, runtime);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("doctor@example.invalid", "fixture-token",
                List.of(new SimpleGrantedAuthority("ROLE_Doctor"))));
        when(appointments.getAppointment(ID)).thenReturn(Map.of("doctor_email", "doctor@example.invalid", "clinical_data", Map.of("age", 50),
                "ultrasound_data", List.of(FIRST, SECOND)));
        when(reports.getReport(ID)).thenReturn(null);
        when(reports.storeReport(anyMap())).thenAnswer(call -> Map.of("report", call.getArgument(0)));
        when(models.predictClinicalData(anyMap())).thenReturn(probability(0.8));
        when(models.predictImage(FIRST)).thenReturn(probability(0.2));
        when(models.predictImage(SECOND)).thenReturn(probability(0.6));
    }
    @AfterEach void close() { SecurityContextHolder.clearContext(); runtime.close(); }
    private static Map<String, Object> probability(double ckd) { return Map.of("probabilities", List.of(1 - ckd, ckd)); }
    private CkdPredictionService.PredictionResult predict() { return service.predict(Map.of("appointment_id", ID)); }
    @SuppressWarnings("unchecked") private Map<String, Object> results() {
        var result = predict(); assertEquals(200, result.status());
        return (Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) result.body().get("report")).get("report")).get("results");
    }
    private static double number(Map<String, Object> results, String key) { return ((Number) results.get(key)).doubleValue(); }
    static Stream<Arguments> malformed() {
        return Stream.of(
                Arguments.of((Object) null), Arguments.of(Map.of()), Arguments.of(Map.of("probabilities", "invalid")),
                Arguments.of(Map.of("probabilities", List.of())), Arguments.of(Map.of("probabilities", List.of(0.2))),
                Arguments.of(Map.of("probabilities", List.of(0.2, 0.3, 0.5))),
                Arguments.of(Map.of("probabilities", List.of(0.8, "0.2"))),
                Arguments.of(Map.of("probabilities", List.of("0.8", 0.2))),
                Arguments.of(Map.of("probabilities", List.of(-0.2, 0.2))),
                Arguments.of(Map.of("probabilities", List.of(1.2, 0.2))),
                Arguments.of(Map.of("probabilities", List.of(0.8, -0.2))),
                Arguments.of(Map.of("probabilities", List.of(0.8, 1.2))),
                Arguments.of(Map.of("probabilities", List.of(Double.NaN, 0.2))),
                Arguments.of(Map.of("probabilities", List.of(0.8, Double.POSITIVE_INFINITY))),
                Arguments.of(Map.of("probabilities", List.of(0.9, 0.9))));
    }
    @ParameterizedTest @MethodSource("malformed")
    void invalidTabularProbabilitiesAre502AndNeverPersisted(Map<String, Object> malformed) {
        when(models.predictClinicalData(anyMap())).thenReturn(malformed);
        assertEquals(502, predict().status()); verify(reports, never()).storeReport(anyMap());
    }
    @Test void invalidImageProbabilitiesAre502AndNeverPersisted() {
        when(models.predictImage(SECOND)).thenReturn(Map.of("probabilities", List.of(-0.2, 0.2)));
        assertEquals(502, predict().status()); verify(reports, never()).storeReport(anyMap());
    }
    @Test void imageMeanIsComputedBeforeEqualWeightFusion() {
        var result = results();
        assertEquals(0.6, number(result, "combined_probability"), 1e-12);
        assertEquals(0.5, number(result, "threshold"), 0);
        assertEquals("CKD", result.get("final_diagnosis"));
        var modalities = (Map<?, ?>) result.get("modality_probabilities");
        assertEquals(0.8, ((Number) modalities.get("tabular")).doubleValue(), 1e-12);
        assertEquals(0.4, ((Number) modalities.get("image")).doubleValue(), 1e-12);
    }
    @Test void weightsAreNormalizedAndConfiguredThresholdChangesTheDecision() {
        settings.setTabularWeight(3); settings.setImageWeight(1); settings.setThreshold(0.75);
        var result = results();
        assertEquals(0.7, number(result, "combined_probability"), 1e-12);
        assertEquals("No CKD", result.get("final_diagnosis"));
        assertEquals(Map.of("method", "weighted_mean", "image_aggregation", "mean", "weights", Map.of("tabular", 0.75, "image", 0.25)), result.get("fusion"));
    }
    @Test void decisionIncludesExactThresholdBoundary() {
        when(models.predictClinicalData(anyMap())).thenReturn(probability(0.6));
        var result = results(); assertEquals(0.5, number(result, "combined_probability"), 1e-12);
        assertEquals("CKD", result.get("final_diagnosis"));
    }
    @Test void zeroImageWeightUsesTabularProbability() {
        settings.setImageWeight(0); assertEquals(0.8, number(results(), "combined_probability"), 1e-12);
    }
    @Test void zeroTabularWeightUsesMeanImageProbability() {
        settings.setTabularWeight(0); assertEquals(0.4, number(results(), "combined_probability"), 1e-12);
    }
    @Test void floatingPointRoundingInProbabilitySumIsAccepted() {
        when(models.predictClinicalData(anyMap())).thenReturn(Map.of("probabilities", List.of(0.20000001, 0.8)));
        assertEquals(200, predict().status());
    }
    @Test void reportReuseReturnsStoredProvenanceWithoutReinterpretingNewSettings() {
        var existing = Map.<String, Object>of("report", Map.of("results", Map.of("threshold", 0.5)));
        when(reports.getReport(ID)).thenReturn(existing); settings.setThreshold(0.9);
        assertEquals(existing, predict().body().get("report")); verifyNoInteractions(models);
        verify(reports, never()).storeReport(anyMap());
    }
}
