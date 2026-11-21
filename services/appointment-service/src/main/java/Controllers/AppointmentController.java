package Controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;
import services.AppointmentService;
import services.AppointmentService.AppointmentResult;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(@Lazy AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping("/images")
    public ResponseEntity<Map<String, Object>> getAllImages(
            @RequestParam("patient_email") String patientEmail
    ) {
        return response(appointmentService.getAllImages(patientEmail));
    }

    @GetMapping("/uploads")
    public ResponseEntity<Map<String, Object>> getAllUploads(
            @RequestParam("patient_email") String patientEmail
    ) {
        return response(appointmentService.getAllUploads(patientEmail));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> bookAppointment(
            @RequestBody Map<String, Object> body
    ) {
        return response(appointmentService.bookAppointment(body));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllAppointments(
           @RequestHeader(HttpHeaders.AUTHORIZATION) String token
     ) {
        return response(appointmentService.getAllAppointments(token));
    }

    @PostMapping("/updateAppointment")
    public ResponseEntity<Map<String, Object>> updateAppointment(
            @RequestBody Map<String, Object> updates
    ) {
        String appointmentId=(String) updates.get("appointmentID");
        String field=(String) updates.get("field");
        String value=(String) updates.get("value");
        Map<String,Object> upd=new HashMap<>();
        upd.put(field,value);
        return response(appointmentService.updateAppointment(appointmentId, upd));
    }
    @GetMapping("/{id}")
    public ResponseEntity<Map<String,Object>> getAppointment(
            @PathVariable("id") String appointmentId){
        return response(appointmentService.getAppointment(appointmentId));
    }
    private static ResponseEntity<Map<String, Object>> response(
            AppointmentResult result
    ) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
