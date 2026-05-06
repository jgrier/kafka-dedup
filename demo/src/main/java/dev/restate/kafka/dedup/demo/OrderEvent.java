package dev.restate.kafka.dedup.demo;

public record OrderEvent(String eventId, String customerId, double amount) {}
