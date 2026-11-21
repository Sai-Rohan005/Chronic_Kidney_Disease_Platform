package services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ckd-prediction")
public class PredictionSettings {
    private long timeoutMillis = 30000;
    private long backoffMillis = 100;
    private int attempts = 3;
    private int imageConcurrency = 4;
    private int queueCapacity = 32;
    private int outboundConcurrency = 8;
    private int circuitMinimumCalls = 10;
    private long circuitOpenMillis = 30000;
    private double tabularWeight = 0.5;
    private double imageWeight = 0.5;
    private double threshold = 0.5;
    private java.util.List<String> allowedImageHosts = java.util.List.of("res.cloudinary.com");
    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long value) { timeoutMillis = value; }
    public long getBackoffMillis() { return backoffMillis; }
    public void setBackoffMillis(long value) { backoffMillis = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public int getImageConcurrency() { return imageConcurrency; }
    public void setImageConcurrency(int value) { imageConcurrency = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public int getOutboundConcurrency() { return outboundConcurrency; }
    public void setOutboundConcurrency(int value) { outboundConcurrency = value; }
    public int getCircuitMinimumCalls() { return circuitMinimumCalls; }
    public void setCircuitMinimumCalls(int value) { circuitMinimumCalls = value; }
    public long getCircuitOpenMillis() { return circuitOpenMillis; }
    public void setCircuitOpenMillis(long value) { circuitOpenMillis = value; }
    public double getTabularWeight() { return tabularWeight; }
    public void setTabularWeight(double value) { tabularWeight = value; }
    public double getImageWeight() { return imageWeight; }
    public void setImageWeight(double value) { imageWeight = value; }
    public double getThreshold() { return threshold; }
    public void setThreshold(double value) { threshold = value; }
    public java.util.List<String> getAllowedImageHosts() { return allowedImageHosts; }
    public void setAllowedImageHosts(java.util.List<String> value) { allowedImageHosts = value; }
    void validate() {
        if (timeoutMillis < 1 || backoffMillis < 1 || attempts < 1 || imageConcurrency < 1
                || outboundConcurrency < 1 || queueCapacity < 1 || circuitMinimumCalls < 1 || circuitOpenMillis < 1
                || !Double.isFinite(tabularWeight) || !Double.isFinite(imageWeight)
                || tabularWeight < 0 || imageWeight < 0 || !Double.isFinite(tabularWeight + imageWeight)
                || tabularWeight + imageWeight <= 0 || !Double.isFinite(threshold) || threshold < 0 || threshold > 1
                || allowedImageHosts == null || allowedImageHosts.isEmpty()
                || allowedImageHosts.stream().anyMatch(host -> host == null || !host.matches("(?i)([a-z0-9-]+\\.)+[a-z]{2,}"))) {
            throw new IllegalArgumentException("Invalid prediction resilience, fusion, or image host configuration");
        }
    }
}
