package Database.schema;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "appointments")
@CompoundIndex(
        name = "unique_doctor_appointment_date",
        def = "{'doctor_email': 1, 'appointment_date': 1}",
        unique = true
)
public class Appointment {

    @Id
    private String id;

    @Field("patient_email")
    private String patientEmail;

    @Field("doctor_email")
    private String doctorEmail;

    @Field("clinical_data")
    private ClinicalData clinicalData;

    @Field("ultrasound_data")
    private List<String> ultrasoundData = new ArrayList<>();

    @Field("status")
    private String status;

    @Field("appointment_date")
    private LocalDate appointmentDate;

    public static class ClinicalData {

        @Field("Age of the patient")
        private Double ageOfPatient;

        @Field("Blood pressure (mm/Hg)")
        private Double bloodPressure;

        @Field("Specific gravity of urine")
        private Double specificGravity;

        @Field("Albumin in urine")
        private Double albumin;

        @Field("Sugar in urine")
        private Double sugar;

        @Field("Red blood cells in urine")
        private String redBloodCells;

        @Field("Pus cells in urine")
        private String pusCells;

        @Field("Pus cell clumps in urine")
        private String pusCellClumps;

        @Field("Bacteria in urine")
        private String bacteria;

        @Field("Random blood glucose level (mg/dl)")
        private Double randomBloodGlucose;

        @Field("Blood urea (mg/dl)")
        private Double bloodUrea;

        @Field("Serum creatinine (mg/dl)")
        private Double serumCreatinine;

        @Field("Sodium level (mEq/L)")
        private Double sodiumLevel;

        @Field("Potassium level (mEq/L)")
        private Double potassiumLevel;

        @Field("Hemoglobin level (gms)")
        private Double hemoglobin;

        @Field("Packed cell volume (%)")
        private Double packedCellVolume;

        @Field("White blood cell count (cells/cumm)")
        private Double whiteBloodCellCount;

        @Field("Red blood cell count (millions/cumm)")
        private Double redBloodCellCount;

        @Field("Hypertension (yes/no)")
        private String hypertension;

        @Field("Diabetes mellitus (yes/no)")
        private String diabetes;

        @Field("Coronary artery disease (yes/no)")
        private String coronaryArteryDisease;

        @Field("Appetite (good/poor)")
        private String appetite;

        @Field("Pedal edema (yes/no)")
        private String pedalEdema;

        @Field("Anemia (yes/no)")
        private String anemia;
    }
}
