package services;

import org.bson.types.ObjectId;
import com.ckd.auth.CurrentCaller;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import services.CkdModelClient.PredictionException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CkdPredictionService {

    private final AppointmentClient appointmentClient;
    private final ReportClient reportClient;
    private final CkdModelClient modelClient;
    private final PredictionRuntime runtime;

    public CkdPredictionService(AppointmentClient appointments, ReportClient reports, CkdModelClient models,
            PredictionRuntime runtime) {
        this.appointmentClient = appointments; this.reportClient = reports; this.modelClient = models; this.runtime = runtime;
    }

    public PredictionResult predict(Map<String, Object> body) { return predict(body, false); }

    public PredictionResult predict(Map<String, Object> body, boolean force) {
        return runtime.timed("ckd.prediction.total", "predict", () -> predictTimed(body, force));
    }

    private PredictionResult predictTimed(Map<String, Object> body, boolean force) {
        CurrentCaller.requireRole("Doctor");
        String id = body == null ? null : stringValue(body.get("appointment_id"));
        if (!ObjectId.isValid(id)) return failure(400, "A valid appointment_id is required");
        String appointmentId = new ObjectId(id).toHexString();
        // Bound the lock table; recheck persisted state under the lock. Mongo uniqueness covers other instances.
        synchronized (runtime.lock(appointmentId)) {
            try { return predictAppointment(appointmentId, force); }
            catch (UpstreamException exception) { return failure(exception.status(), exception.getMessage()); }
            catch (PredictionException | RestClientException exception) {
                return failure(502, "An upstream service returned invalid prediction data");
            }
        }
    }

    private PredictionResult predictAppointment(String appointmentId, boolean force) {
        Map<String, Object> appointment = appointmentClient.getAppointment(appointmentId);
        if (appointment == null) return failure(404, "Appointment not found");
        CurrentCaller.requireAppointment(appointment);
        Map<String, Object> existing = reportClient.getReport(appointmentId);
        if (existing != null && !force) return success(existing);

        if (!(appointment.get("clinical_data") instanceof Map<?, ?> clinicalData)) {
            return failure(400, "Appointment has no valid clinical_data");
        }
        List<String> urls = imageUrls(appointment.get("ultrasound_data"));
        if (urls == null || urls.isEmpty() || urls.stream().anyMatch(url -> !ImageUrlPolicy.allowed(url, runtime.settings))) {
            return failure(400, "Appointment has no valid allow-listed HTTPS ultrasound images");
        }
        Map<String, Object> tabular = modelClient.predictClinicalData(toStringKeyMap(clinicalData));
        List<java.util.concurrent.Callable<Map<String, Object>>> tasks = new ArrayList<>();
        for (String url : urls) tasks.add(() -> modelClient.predictImage(url));
        List<Map<String, Object>> predictions = runtime.images(tasks);
        double imageProbability = predictions.stream().mapToDouble(CkdPredictionService::ckdProbability).average().orElseThrow();
        double tabularProbability = ckdProbability(tabular);
        var settings = runtime.settings;
        double tabularWeight = settings.getTabularWeight() / (settings.getTabularWeight() + settings.getImageWeight());
        double imageWeight = settings.getImageWeight() / (settings.getTabularWeight() + settings.getImageWeight());
        double combined = tabularWeight * tabularProbability + imageWeight * imageProbability;
        double threshold = settings.getThreshold();
        Map<String, Object> imageSummary = new LinkedHashMap<>();
        imageSummary.put("prediction", imageProbability >= threshold ? 1 : 0);
        imageSummary.put("probabilities", List.of(1.0 - imageProbability, imageProbability));
        imageSummary.put("per_image", predictions);
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("final_diagnosis", combined >= threshold ? "CKD" : "No CKD");
        results.put("combined_probability", combined);
        results.put("threshold", threshold);
        results.put("modality_probabilities", Map.of("tabular", tabularProbability, "image", imageProbability));
        results.put("fusion", Map.of("method", "weighted_mean", "image_aggregation", "mean",
                "weights", Map.of("tabular", tabularWeight, "image", imageWeight)));
        Map<String, Object> fields = Map.of("tabres", tabular, "unetRes", imageSummary, "results", results);
        Map<String, Object> report;
        try {
            if (existing != null) report = reportClient.updateReport(appointmentId, fields);
            else {
                Map<String, Object> payload = new LinkedHashMap<>(fields);
                payload.put("appointment_id", appointmentId);
                report = reportClient.storeReport(payload);
            }
        } catch (UpstreamException exception) {
            if (exception.status() != 409) throw exception;
            // Another instance won the insert race. Return its authoritative result (or replace it for force).
            report = force ? reportClient.updateReport(appointmentId, fields) : reportClient.getReport(appointmentId);
            if (report == null) throw new UpstreamException(503, false, "Concurrent report creation could not be resolved", exception);
        }
        return success(report);
    }

    private static PredictionResult success(Map<String, Object> report) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "CKD prediction completed successfully");
        response.put("report", report); // Preserve the existing report response envelope.
        if (report != null && report.get("report") instanceof Map<?, ?> stored && stored.get("results") instanceof Map<?, ?> results) {
            if (results.containsKey("modality_probabilities")) response.put("modality_probabilities", results.get("modality_probabilities"));
            if (results.containsKey("fusion")) response.put("fusion", results.get("fusion"));
        }
        return new PredictionResult(200, response);
    }

    private static double ckdProbability(Map<String, Object> prediction) {
        Object value = prediction == null ? null : prediction.get("probabilities");
        if (!(value instanceof List<?> probabilities) || probabilities.size() != 2
                || !(probabilities.get(0) instanceof Number nonCkd)
                || !(probabilities.get(1) instanceof Number ckd)) {
            throw new PredictionException("Prediction service returned invalid probabilities");
        }
        double first = nonCkd.doubleValue(), second = ckd.doubleValue();
        if (!Double.isFinite(first) || !Double.isFinite(second) || first < 0 || first > 1 || second < 0 || second > 1
                || Math.abs(first + second - 1.0) > 1e-6) {
            throw new PredictionException("Prediction service returned invalid probabilities");
        }
        return second;
    }

    private static List<String> imageUrls(Object value) {
        if (!(value instanceof List<?> values)) {
            return null;
        }
        List<String> urls = new ArrayList<>(values.size());
        for (Object image : values) {
            if (!(image instanceof String url) || url.isBlank()) {
                return null;
            }
            urls.add(url);
        }
        return urls;
    }

    private static Map<String, Object> toStringKeyMap(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, value);
            }
        });
        return result;
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string.trim() : null;
    }

    private static PredictionResult failure(int status, String message) {
        return new PredictionResult(status, Map.of("message", message));
    }

    public record PredictionResult(int status, Map<String, Object> body) {}
}
