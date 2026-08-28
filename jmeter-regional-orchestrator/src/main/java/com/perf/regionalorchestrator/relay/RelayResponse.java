package com.perf.regionalorchestrator.relay;

/** The worker's answer, or a synthesised {@code 502}/{@code 504} when it gave none. */
public record RelayResponse(int status, byte[] body, String contentType) {
}
