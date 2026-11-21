package Database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Repository
@Lazy
public class AppointmentRepository {

    private static final Duration BOOKING_LEASE = Duration.ofMinutes(30);
    private final MongoCollection<Document> appointments;

    public AppointmentRepository(DB db) {
        this.appointments = db.getDatabase().getCollection("appointments");
        this.appointments.createIndex(
                Indexes.ascending("doctor_email", "appointment_date"),
                new IndexOptions()
                        .unique(true)
                        .name("unique_doctor_appointment_date")
        );
        this.appointments.createIndex(
                Indexes.ascending("booking_expires_at"),
                new IndexOptions()
                        .expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS)
                        .name("expire_incomplete_appointment_bookings")
        );
    }

    public List<String> getAllImages(String patientEmail) {
        List<String> allImages = new ArrayList<>();
        for (Document appointment : appointments.find(
                Filters.eq("patient_email", patientEmail))) {
            List<?> ultrasoundData = appointment.getList(
                    "ultrasound_data", Object.class);
            if (ultrasoundData != null) {
                for (Object image : ultrasoundData) {
                    if (image instanceof String imageUrl) {
                        allImages.add(imageUrl);
                    }
                }
            }
        }
        return allImages;
    }

    public String reserveAppointment(
            String patientEmail,
            String doctorEmail,
            LocalDate date,
            Object clinicalData
    ) {
        Document appointment = new Document("patient_email", patientEmail)
                .append("doctor_email", doctorEmail)
                .append("clinical_data", clinicalData)
                .append("ultrasound_data", new ArrayList<String>())
                .append("status", "uploading")
                .append("booking_expires_at", bookingLeaseExpiry())
                .append("appointment_date", date);
        try {
            appointments.insertOne(appointment);
        } catch (com.mongodb.MongoWriteException exception) {
            if (exception.getError().getCode() == 11000) {
                throw new DuplicateKeyException(
                        "Doctor already has an appointment on this date", exception
                );
            }
            throw exception;
        }
        return appointment.getObjectId("_id").toHexString();
    }

    public boolean completeAppointmentBooking(String id, List<String> ultrasoundImages) {
        UpdateResult result = appointments.updateOne(
                Filters.and(
                        Filters.eq("_id", new ObjectId(id)),
                        Filters.eq("status", "uploading")
                ),
                Updates.combine(
                        Updates.set("ultrasound_data", ultrasoundImages),
                        Updates.set("status", "pending"),
                        Updates.unset("booking_expires_at")
                )
        );
        return result.getMatchedCount() > 0;
    }

    public boolean renewAppointmentBooking(String id) {
        UpdateResult result = appointments.updateOne(
                Filters.and(
                        Filters.eq("_id", new ObjectId(id)),
                        Filters.eq("status", "uploading")
                ),
                Updates.set("booking_expires_at", bookingLeaseExpiry())
        );
        return result.getMatchedCount() > 0;
    }
    public Document getAppointment(String id){
        if (!ObjectId.isValid(id)) {
            return null;
        }
        try{
            return appointments.find(Filters.eq("_id",new ObjectId(id))).first();
        }catch (Exception e){
            String s = "Error at get appointment : ";
            return new Document("error",s);
        }
    }
    public boolean deleteAppointment(String id) {
        return appointments.deleteOne(
                Filters.and(
                        Filters.eq("_id", new ObjectId(id)),
                        Filters.eq("status", "uploading")
                )
        ).getDeletedCount() > 0;
    }


    public List<Document> getAllAppointments(String email, String role) {
        String emailField = switch (role) {
            case "Patient" -> "patient_email";
            case "Doctor" -> "doctor_email";
            default -> throw new IllegalArgumentException("Unsupported role");
        };
        return appointments.find(Filters.eq(emailField, email))
                .into(new ArrayList<>());
    }

    public boolean updateAppointment(String id, String field, Object value) {
        if (!ObjectId.isValid(id)) {
            return false;
        }
        try {
            UpdateResult result = appointments.updateOne(
                    Filters.eq("_id", new ObjectId(id)),
                    Updates.set(field, value)
            );
            return result.getMatchedCount() > 0;
        } catch (com.mongodb.MongoWriteException exception) {
            if (exception.getError().getCode() == 11000) {
                throw new DuplicateKeyException(
                        "Doctor already has an appointment on this date", exception
                );
            }
            throw exception;
        }
    }

    private static Date bookingLeaseExpiry() {
        return new Date(System.currentTimeMillis() + BOOKING_LEASE.toMillis());
    }
}
