package Controllers;

import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import services.ReportService;
import services.ReportService.ReportResult;

import java.util.Map;

@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(@Lazy ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/{appointmentId}")
    public ResponseEntity<Map<String, Object>> getReportById(@PathVariable("appointmentId") String appointmentId) {
        return response(reportService.getReport(appointmentId));
    }

    @PostMapping("/getReport")
    public ResponseEntity<Map<String, Object>> getReport(
            @RequestBody Map<String,Object> body
    ) {
        String appointmentId=(String)body.get("appointmentId");
        return response(reportService.getReport(appointmentId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createReport(
            @RequestBody Map<String, Object> body
    ) {
        return response(reportService.createReport(body));
    }

    @PatchMapping("/{appointmentId}")
    public ResponseEntity<Map<String, Object>> updateReport(
            @PathVariable String appointmentId,
            @RequestBody Map<String, Object> updates
    ) {
        return response(reportService.updateReport(appointmentId, updates));
    }

    private static ResponseEntity<Map<String, Object>> response(ReportResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
