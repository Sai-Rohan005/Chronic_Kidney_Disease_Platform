package ckd;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.gson.*;
import com.mongodb.client.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises packaged services through HTTP; only the external ML implementation is replaced. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformIT {
    private static final Gson JSON = new Gson();
    private static final String PASSWORD = "container-fixture-password";
    private static final String DATABASE = "CKD_JAVA";
    private static final String FIRST = "https://res.cloudinary.com/demo/a.png";
    private static final String SECOND = "https://res.cloudinary.com/demo/b.png";
    private static final Network NETWORK = Network.newNetwork();
    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.16")
            .withNetwork(NETWORK).withNetworkAliases("mongo");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.2")
            .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
    @Container static final GenericContainer<?> ML = new GenericContainer<>("wiremock/wiremock:3.13.2")
            .withNetwork(NETWORK).withNetworkAliases("ml").withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));
    private final String secret = secret();
    private final List<GenericContainer<?>> services = new ArrayList<>();
    private GenericContainer<?> auth, appointments, reports, prediction;
    private MongoClient mongo;
    private MongoDatabase database;
    private HttpClient http;
    private WireMock ml;

    private static String secret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
    private GenericContainer<?> service(String directory, String artifact, int port, String alias) {
        Path jar = Path.of("..", "services", directory, "target", artifact + "-0.0.1-SNAPSHOT.jar").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(jar), "Run the root reactor through verify so service jars are packaged");
        var image = new ImageFromDockerfile().withFileFromPath("app.jar", jar)
                .withDockerfileFromBuilder(builder -> builder.from("eclipse-temurin:21-jre")
                        .copy("app.jar", "/app.jar").entryPoint("java", "-jar", "/app.jar").build());
        GenericContainer<?> container = new GenericContainer<>(image).withNetwork(NETWORK).withNetworkAliases(alias)
                .withExposedPorts(port).withEnv("JWT_SECRET", secret)
                .withEnv("MONGODB_URI", "mongodb://mongo:27017/?directConnection=true")
                .withEnv("MONGODB_DATABASE", DATABASE).withEnv("REDIS_HOST", "redis")
                .withEnv("LOGIN_RATE_LIMIT", "1000")
                .withEnv("APPOINTMENT_CLIENT_URL", "http://appointment:8082/appointments")
                .withEnv("REPORT_CLIENT_URL", "http://report:8083/reports")
                .withEnv("CKD_TABULAR_PREDICTION_URL", "http://ml:8080/routes/tab_predict")
                .withEnv("CKD_IMAGE_PREDICTION_URL", "http://ml:8080/routes/unet_predict")
                .waitingFor(Wait.forHttp("/actuator/health").forPort(port).forStatusCode(401))
                .withStartupTimeout(Duration.ofMinutes(2));
        services.add(container);
        return container;
    }
    @BeforeAll void startServices() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        mongo = MongoClients.create(MONGO.getReplicaSetUrl()); database = mongo.getDatabase(DATABASE);
        ml = new WireMock(ML.getHost(), ML.getMappedPort(8080));
        auth = service("auth-service", "CKD_AUTHSERVICE", 8081, "auth");
        appointments = service("appointment-service", "CKD_APPOINTMENT_SERVICE", 8082, "appointment");
        reports = service("report-service", "CKD_REPORT_SERVICE", 8083, "report");
        prediction = service("prediction-service", "CKD_PREDICTION", 8084, "prediction");
        // Avoid a cold-start race between each service's collection initialization.
        for (String collection : List.of("users", "doctors", "appointments", "reports")) database.createCollection(collection);
        org.testcontainers.lifecycle.Startables.deepStart(Stream.of(auth, appointments, reports, prediction)).join();
    }
    @AfterAll void close() {
        for (int i = services.size() - 1; i >= 0; i--) services.get(i).stop();
        if (http != null) http.close();
        if (mongo != null) mongo.close();
        ML.stop(); REDIS.stop(); MONGO.stop();
        NETWORK.close();
    }
    @BeforeEach void reset() {
        for (String collection : List.of("users", "appointments", "reports")) database.getCollection(collection).deleteMany(new Document());
        ml.resetMappings(); ml.resetRequests();
        // Controlled response fixtures, not measured model results.
        ml.register(post(urlEqualTo("/routes/tab_predict")).willReturn(okJson("{\"prediction\":1,\"probabilities\":[0.2,0.8]}")));
        ml.register(post(urlEqualTo("/routes/unet_predict")).withRequestBody(containing("a.png"))
                .willReturn(okJson("{\"prediction\":0,\"probabilities\":[0.8,0.2]}")));
        ml.register(post(urlEqualTo("/routes/unet_predict")).withRequestBody(containing("b.png"))
                .willReturn(okJson("{\"prediction\":1,\"probabilities\":[0.4,0.6]}")));
    }
    private Response request(GenericContainer<?> service, int port, String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + service.getHost() + ":" + service.getMappedPort(port) + path))
                .timeout(Duration.ofSeconds(45)).header("X-Request-Id", "container-contract-test");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
        var result = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(result.statusCode(), JsonParser.parseString(result.body()).getAsJsonObject());
    }
    record Response(int status, JsonObject body) {}
    private String registerAndLogin(String email, boolean doctor) throws Exception {
        var registered = request(auth, 8081, "POST", "/auth/register", Map.of("name", "Fixture", "email", email, "password", PASSWORD), null);
        assertEquals(201, registered.status());
        if (doctor) database.getCollection("users").updateOne(eq("email", email), set("role", "Doctor"));
        return login(email);
    }
    private String login(String email) throws Exception {
        var login = request(auth, 8081, "POST", "/auth/login", Map.of("email", email, "password", PASSWORD), null);
        assertEquals(200, login.status()); return login.body().get("token").getAsString();
    }
    record Fixture(String patient, String doctor, String appointment) {}
    private Fixture appointment() throws Exception {
        String patient = registerAndLogin("patient@example.invalid", false);
        String doctor = registerAndLogin("doctor@example.invalid", true);
        var booked = request(appointments, 8082, "POST", "/appointments", Map.of("patient_email", "patient@example.invalid",
                "doctor_email", "doctor@example.invalid", "appointment_date", "2030-01-15", "clinical_data", Map.of("age", 50)), patient);
        assertEquals(201, booked.status());
        return new Fixture(patient, doctor, booked.body().get("appointment_id").getAsString());
    }
    @Test void registerLoginTokenWorksThenLogoutRevokesItInRedis() throws Exception {
        String token = registerAndLogin("patient@example.invalid", false);
        Document user = database.getCollection("users").find(eq("email", "patient@example.invalid")).first();
        assertNotNull(user); assertFalse(user.containsKey("password")); assertEquals("Patient", user.getString("role"));
        assertTrue(BCrypt.checkpw(PASSWORD, user.getString("passwordHash")));
        String jti = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8))
                .getAsJsonObject().get("jti").getAsString();
        long ttl = Long.parseLong(REDIS.execInContainer("redis-cli", "TTL", "auth:token:" + jti).getStdout().trim());
        assertTrue(ttl > 3500 && ttl <= 3600);
        assertEquals(200, request(auth, 8081, "GET", "/auth/getRoleByToken", null, token).status());
        assertEquals(200, request(auth, 8081, "POST", "/auth/logout", null, token).status());
        assertEquals("0", REDIS.execInContainer("redis-cli", "EXISTS", "auth:token:" + jti).getStdout().trim());
        assertEquals(401, request(auth, 8081, "GET", "/auth/getRoleByToken", null, token).status());
        assertEquals(401, request(appointments, 8082, "GET", "/appointments", null, token).status());
    }
    @Test void bookThenFetchUsesRealMongoAndRejectsAnotherPatient() throws Exception {
        var fixture = appointment();
        var fetched = request(appointments, 8082, "GET", "/appointments/" + fixture.appointment(), null, fixture.patient());
        assertEquals(200, fetched.status());
        assertEquals(50, fetched.body().getAsJsonObject("data").getAsJsonObject("clinical_data").get("age").getAsInt());
        String other = registerAndLogin("other@example.invalid", false);
        assertEquals(403, request(appointments, 8082, "GET", "/appointments/" + fixture.appointment(), null, other).status());
        assertEquals(1, database.getCollection("appointments").countDocuments());
    }
    @Test void createGetReportAndMongoUniqueIndexPreventDuplicates() throws Exception {
        var fixture = appointment();
        Map<String, Object> report = Map.of("appointment_id", fixture.appointment(), "results", Map.of("final_diagnosis", "No CKD"));
        assertEquals(201, request(reports, 8083, "POST", "/reports", report, fixture.doctor()).status());
        var read = request(reports, 8083, "GET", "/reports/" + fixture.appointment(), null, fixture.patient());
        assertEquals(200, read.status()); assertEquals("No CKD", read.body().getAsJsonObject("report").getAsJsonObject("results").get("final_diagnosis").getAsString());
        assertEquals(409, request(reports, 8083, "POST", "/reports", report, fixture.doctor()).status());
        assertThrows(com.mongodb.MongoWriteException.class, () -> database.getCollection("reports").insertOne(new Document("appointment_id", fixture.appointment())));
        assertEquals(1, database.getCollection("reports").countDocuments());
    }
    private void images(String appointment) {
        // External image storage fixture: do not call Cloudinary or imply its upload path is covered.
        database.getCollection("appointments").updateOne(eq("_id", new ObjectId(appointment)),
                combine(set("ultrasound_data", List.of(FIRST, SECOND)), set("status", "pending"), unset("booking_expires_at")));
    }
    @Test void predictionEndToEndForwardsIdentityFusesPersistsAndReusesReport() throws Exception {
        var fixture = appointment(); images(fixture.appointment());
        var payload = Map.of("appointment_id", fixture.appointment());
        assertEquals(403, request(prediction, 8084, "POST", "/predictions", payload, fixture.patient()).status());
        var result = request(prediction, 8084, "POST", "/predictions", payload, fixture.doctor());
        assertEquals(200, result.status());
        var stored = request(reports, 8083, "GET", "/reports/" + fixture.appointment(), null, fixture.patient());
        assertEquals(200, stored.status());
        var results = stored.body().getAsJsonObject("report").getAsJsonObject("results");
        assertEquals(0.6, results.get("combined_probability").getAsDouble(), 1e-12);
        assertEquals("CKD", results.get("final_diagnosis").getAsString());
        assertEquals("weighted_mean", results.getAsJsonObject("fusion").get("method").getAsString());
        assertEquals(200, request(prediction, 8084, "POST", "/predictions", payload, fixture.doctor()).status());
        assertEquals(1, database.getCollection("reports").countDocuments());
        ml.verifyThat(1, postRequestedFor(urlEqualTo("/routes/tab_predict"))
                .withHeader("Authorization", matching("Bearer .+" )).withHeader("X-Request-Id", equalTo("container-contract-test")));
        ml.verifyThat(2, postRequestedFor(urlEqualTo("/routes/unet_predict"))
                .withHeader("Authorization", matching("Bearer .+")).withHeader("X-Request-Id", equalTo("container-contract-test")));
    }
    @Test void partialMlFailureNeverWritesAReport() throws Exception {
        var fixture = appointment(); images(fixture.appointment());
        ml.register(post(urlEqualTo("/routes/unet_predict")).withRequestBody(containing("b.png")).willReturn(serverError()));
        assertEquals(502, request(prediction, 8084, "POST", "/predictions", Map.of("appointment_id", fixture.appointment()), fixture.doctor()).status());
        assertEquals(0, database.getCollection("reports").countDocuments());
    }
}
