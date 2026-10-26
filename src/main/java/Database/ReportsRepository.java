package Database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
@Lazy
public class ReportsRepository {

    private final MongoCollection<Document> reports;

    public ReportsRepository(DB db) {
        this.reports = db.getDatabase().getCollection("reports");
    }

    public Document getReport(String appointmentId) {
        return reports.find(Filters.eq("appointment_id", appointmentId)).first();
    }

    public Document createReport(String appointmentId, Map<String, Object> reportData) {
        Document report = new Document("appointment_id", appointmentId);
        reportData.forEach(report::append);
        reports.insertOne(report);
        return report;
    }

    public Document savePrediction(
            String appointmentId,
            Map<String, Object> predictionData
    ) {
        List<org.bson.conversions.Bson> updates = new ArrayList<>();
        updates.add(Updates.setOnInsert("appointment_id", appointmentId));
        predictionData.forEach((field, value) ->
                updates.add(Updates.set(field, value)));
        reports.updateOne(
                Filters.eq("appointment_id", appointmentId),
                Updates.combine(updates),
                new UpdateOptions().upsert(true)
        );
        return getReport(appointmentId);
    }

    public boolean updateReport(String appointmentId, Map<String, Object> updates) {
        List<org.bson.conversions.Bson> mongoUpdates = new ArrayList<>();
        updates.forEach((field, value) ->
                mongoUpdates.add(Updates.set(field, value)));

        UpdateResult result = reports.updateOne(
                Filters.eq("appointment_id", appointmentId),
                Updates.combine(mongoUpdates)
        );
        return result.getMatchedCount() > 0;
    }
}
