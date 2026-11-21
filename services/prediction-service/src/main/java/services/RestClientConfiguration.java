package services;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfiguration {
    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder(PredictionSettings settings) {
        var http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(settings.getTimeoutMillis()))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(java.time.Duration.ofMillis(settings.getTimeoutMillis()));
        return RestClient.builder().requestFactory(factory);
    }
}
