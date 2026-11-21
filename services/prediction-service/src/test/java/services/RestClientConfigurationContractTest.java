package services;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class RestClientConfigurationContractTest {
    @Test void clientsReceiveIndependentBuilders() {
        try (var context = new AnnotationConfigApplicationContext(RestClientConfiguration.class, PredictionSettings.class)) {
            assertNotSame(context.getBean(RestClient.Builder.class), context.getBean(RestClient.Builder.class));
        }
    }
}
