package services;

import Database.ReportsRepository;
import com.ckd.auth.CurrentCaller;
import org.bson.Document;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@Lazy
public class ReportService {

    private static final Set<String> REPORT_FIELDS = Set.of(
            "tabres", "unetRes", "results", "reports", "analysis"
    );

    private final ReportsRepository reportsRepository;

    public ReportService(@Lazy ReportsRepository reportsRepository) {
        this.reportsRepository = reportsRepository;
    }

    public ReportResult getReport(String appointmentId) {
        if (!validAppointmentId(appointmentId)) {
            return failure(400, "A valid appointment_id is required");
        }

        appointmentId = new org.bson.types.ObjectId(appointmentId).toHexString();
        requireAppointment(appointmentId);
        Document report = reportsRepository.getReport(appointmentId);
        if (report == null) {
            return failure(404, "Report not found");
        }
        return new ReportResult(200, Map.of("report", report));
    }

    public ReportResult createReport(Map<String, Object> body) {
        CurrentCaller.requireRole("Doctor");
        String appointmentId = stringValue(body.get("appointment_id"));
        if (!validAppointmentId(appointmentId)) {
            return failure(400, "A valid appointment_id is required");
        }

        appointmentId = new org.bson.types.ObjectId(appointmentId).toHexString();
        requireAppointment(appointmentId);
        Map<String, Object> reportData = extractReportFields(body);
        if (reportData.isEmpty()) {
            return failure(400, "At least one report field is required");
        }
        if (reportsRepository.getReport(appointmentId) != null) {
            return failure(409, "A report already exists for this appointment");
        }

        Document report;
        try { report = reportsRepository.createReport(appointmentId, reportData); }
        catch (com.mongodb.MongoWriteException exception) {
            if (exception.getError().getCode() == 11000) return failure(409, "A report already exists for this appointment");
            throw exception;
        }
        return new ReportResult(201, Map.of(
                "message", "Report created successfully",
                "report", report
        ));
    }

    public ReportResult updateReport(
            String appointmentId,
            Map<String, Object> requestedUpdates
    ) {
        CurrentCaller.requireRole("Doctor");
        if (!validAppointmentId(appointmentId)) {
            return failure(400, "A valid appointment_id is required");
        }
        appointmentId = new org.bson.types.ObjectId(appointmentId).toHexString();
        requireAppointment(appointmentId);
        if (requestedUpdates == null || requestedUpdates.isEmpty()) {
            return failure(400, "At least one report field is required");
        }

        Map<String, Object> updates = extractReportFields(requestedUpdates);
        if (updates.size() != requestedUpdates.size()) {
            return failure(400, "Only report fields can be updated");
        }

        if (!reportsRepository.updateReport(appointmentId, updates)) {
            return failure(404, "Report not found");
        }

        Document updatedReport = reportsRepository.getReport(appointmentId);
        return new ReportResult(200, Map.of(
                "message", "Report updated successfully",
                "report", updatedReport
        ));
    }

    private static Map<String, Object> extractReportFields(
            Map<String, Object> data
    ) {
        Map<String, Object> fields = new LinkedHashMap<>();
        data.forEach((field, value) -> {
            if (REPORT_FIELDS.contains(field)) {
                fields.put(field, value);
            }
        });
        return fields;
    }

    private static boolean validAppointmentId(String appointmentId) {
        return org.bson.types.ObjectId.isValid(appointmentId);
    }

    private void requireAppointment(String id) {
        Document appointment = reportsRepository.getAppointment(id);
        if (appointment == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Appointment not found");
        CurrentCaller.requireAppointment(appointment);
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string.trim() : null;
    }

    private static ReportResult failure(int status, String message) {
        return new ReportResult(status, Map.of("message", message));
    }

    public record ReportResult(int status, Map<String, Object> body) {}
}
