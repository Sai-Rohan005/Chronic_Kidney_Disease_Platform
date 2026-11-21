package services;

import Database.AppointmentRepository;
import com.ckd.auth.CurrentCaller;
import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Lazy
public class AppointmentService {

    private static final Logger logger =
            LoggerFactory.getLogger(AppointmentService.class);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> UPDATABLE_FIELDS = Set.of(
            "status", "clinical_data", "ultrasound_data", "appointment_date"
    );

    private final AppointmentRepository appointmentRepository;
    private final Cloudinary cloudinary;

    public AppointmentService(
            @Lazy AppointmentRepository appointmentRepository,
            Cloudinary cloudinary
    ) {
        this.appointmentRepository = appointmentRepository;
        this.cloudinary = cloudinary;
    }

    public AppointmentResult getAllImages(String rawPatientEmail) {
        String patientEmail = normalizeEmail(rawPatientEmail);
        if (!validEmail(patientEmail)) {
            return failure(400, "A valid patient email is required");
        }
        CurrentCaller.requirePatient(patientEmail);
        return new AppointmentResult(200, Map.of(
                "images", appointmentRepository.getAllImages(patientEmail)
        ));
    }

    public AppointmentResult getAllUploads(String rawPatientEmail) {
        String patientEmail = normalizeEmail(rawPatientEmail);
        if (!validEmail(patientEmail)) {
            return failure(400, "A valid patient email is required");
        }
        CurrentCaller.requirePatient(patientEmail);
        return new AppointmentResult(200, Map.of(
                "uploads", appointmentRepository.getAllImages(patientEmail)
        ));
    }

    public AppointmentResult bookAppointment(Map<String, Object> body) {
        String patientEmail = normalizeEmail(stringValue(body.get("patient_email")));
        String doctorEmail = normalizeEmail(stringValue(body.get("doctor_email")));
        Object dateValue = body.containsKey("appointment_date")
                ? body.get("appointment_date") : body.get("date");

        if (!validEmail(patientEmail) || !validEmail(doctorEmail)) {
            return failure(400, "Valid patient_email and doctor_email are required");
        }

        CurrentCaller.requirePatient(patientEmail);
        LocalDate appointmentDate;
        try {
            appointmentDate = LocalDate.parse(stringValue(dateValue));
        } catch (DateTimeParseException | NullPointerException exception) {
            return failure(400, "date must be an ISO date in YYYY-MM-DD format");
        }

        List<String> ultrasoundImages = stringList(body.get("ultrasound_images"));
        if (ultrasoundImages == null) {
            return failure(400, "ultrasound_images must be a list of strings");
        }

        Object clinicalData = body.get("clinical_data");
        if (clinicalData != null && !(clinicalData instanceof Map<?, ?>)) {
            return failure(400, "clinical_data must be an object");
        }

        String appointmentId;
        try {
            appointmentId = appointmentRepository.reserveAppointment(
                    patientEmail,
                    doctorEmail,
                    appointmentDate,
                    clinicalData
            );
        } catch (DuplicateKeyException exception) {
            return failure(409, "Doctor already has an appointment on this date");
        }

//        List<String> cloudinaryImageUrls = new ArrayList<>(ultrasoundImages.size());
//        try {
//            for (String image : ultrasoundImages) {
//                Map<?, ?> uploadResult = cloudinary.uploader().upload(
//                        image, ObjectUtils.emptyMap()
//                );
//                Object secureUrl = uploadResult == null
//                        ? null : uploadResult.get("secure_url");
//                if (!(secureUrl instanceof String imageUrl) || imageUrl.isBlank()) {
//                    logger.error("Cloudinary upload response did not contain a secure URL");
//                    removeIncompleteAppointment(appointmentId);
//                    return failure(502, "Unable to upload ultrasound images");
//                }
//                cloudinaryImageUrls.add(imageUrl);
//                if (!appointmentRepository.renewAppointmentBooking(appointmentId)) {
//                    logger.error("Appointment booking lease expired for {}", appointmentId);
//                    removeIncompleteAppointment(appointmentId);
//                    return failure(409, "Appointment booking reservation expired");
//                }
//            }
//        } catch (IOException exception) {
//            logger.error("Failed to upload appointment ultrasound images to Cloudinary", exception);
//            removeIncompleteAppointment(appointmentId);
//            return failure(502, "Unable to upload ultrasound images");
//        }

//        if (!appointmentRepository.completeAppointmentBooking(
//                appointmentId, cloudinaryImageUrls
//        )) {
//            logger.error("Could not complete appointment booking {}", appointmentId);
//            removeIncompleteAppointment(appointmentId);
//            return failure(500, "Unable to complete appointment booking");
//        }
        return new AppointmentResult(201, Map.of(
                "message", "Appointment booked successfully",
                "appointment_id", appointmentId,
                "status", "pending"
        ));
    }


    public AppointmentResult getAllAppointments(String token) {
        var user = CurrentCaller.user();

        List<Document> appointments = appointmentRepository.getAllAppointments(
                normalizeEmail(user.email()),
                user.role()
        );
        return new AppointmentResult(200, Map.of("appointments", appointments));
    }



    public AppointmentResult getAppointment(String id){
        if(!ObjectId.isValid(id)){
            return failure(400 ,"A valid appointment ID is required");
        }
        Document doc=appointmentRepository.getAppointment(id);
        if(doc==null){
            return failure(404,"Appointment not found");
        }
        if(doc.containsKey("error")){
            return failure(400,(String) doc.get("error"));
        }
        CurrentCaller.requireAppointment(doc);
        return new AppointmentResult(200 ,Map.of("data",doc));
    }

    public AppointmentResult updateAppointment(
            String appointmentId,
            Map<String, Object> updates
    ) {
        if (!ObjectId.isValid(appointmentId)) {
            return failure(400, "A valid appointment ID is required");
        }
        AppointmentResult access = getAppointment(appointmentId);
        if (access.status() != 200) return access;
        if (updates == null || updates.size() != 1) {
            return failure(400, "Provide exactly one supported appointment field to update");
        }

        Map.Entry<String, Object> update = updates.entrySet().iterator().next();
        String field = update.getKey();
        Object value = update.getValue();
        if (!UPDATABLE_FIELDS.contains(field)) {
            return failure(400, "Unsupported appointment field");
        }

        if ("status".equals(field)) {
            if (!(value instanceof String status) || status.isBlank()) {
                return failure(400, "status must be a non-empty string");
            }
        } else if ("appointment_date".equals(field)) {
            try {
                value = LocalDate.parse(stringValue(value));
            } catch (DateTimeParseException | NullPointerException exception) {
                return failure(400, "appointment_date must use YYYY-MM-DD format");
            }
        } else if ("clinical_data".equals(field)) {
            if (!(value instanceof Map<?, ?> clinicalData)) {
                return failure(400, "clinical_data must be an object");
            }
            Document clinicalDocument = new Document();
            for (Map.Entry<?, ?> entry : clinicalData.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    return failure(400, "clinical_data keys must be strings");
                }
                clinicalDocument.put(key, entry.getValue());
            }
            value = clinicalDocument;
        } else if ("ultrasound_data".equals(field)) {
            List<String> images = stringList(value);
            if (images == null) {
                return failure(400, "ultrasound_data must be a list of strings");
            }
            value = images;
        }

        try {
            if (!appointmentRepository.updateAppointment(appointmentId, field, value)) {
                return failure(404, "Appointment not found");
            }
        } catch (DuplicateKeyException exception) {
            return failure(409, "Doctor already has an appointment on this date");
        }
        return new AppointmentResult(200, Map.of(
                "message", "Appointment updated successfully"
        ));
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List<?> values)) {
            return null;
        }
        List<String> strings = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String string)) {
                return null;
            }
            strings.add(string);
        }
        return strings;
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean validEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    private static AppointmentResult failure(int status, String message) {
        return new AppointmentResult(status, Map.of("message", message));
    }

    private void removeIncompleteAppointment(String appointmentId) {
        try {
            if (!appointmentRepository.deleteAppointment(appointmentId)) {
                logger.error(
                        "Incomplete appointment {} could not be removed",
                        appointmentId
                );
            }
        } catch (RuntimeException exception) {
            logger.error(
                    "Failed to remove incomplete appointment {}",
                    appointmentId,
                    exception
            );
        }
    }

    public record AppointmentResult(int status, Map<String, Object> body) {}
}
