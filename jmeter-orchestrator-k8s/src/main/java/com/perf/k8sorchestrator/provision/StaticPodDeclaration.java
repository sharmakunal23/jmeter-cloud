package com.perf.k8sorchestrator.provision;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * A validated operator declaration of an
 * externally deployed worker.
 *
 * <p>Both fields are typed by hand by an operator and then used by the
 * control plane: {@code podName} becomes the registry primary key (and must
 * equal the {@code workerId} the worker stamps on its metrics, or the
 * metrics join breaks), and {@code baseUrl} is an address this service will
 * issue HTTP requests to. That makes this the one place in the track where
 * input validation is load-bearing rather than cosmetic, so it lives in its
 * own value object — constructible and testable without a Spring context.
 *
 * <p><b>Security note.</b> {@code baseUrl} is an operator-supplied URL the
 * control plane will POST to, which is a request-forgery surface. This
 * validator rejects the cheap abuses (non-HTTP schemes, embedded
 * credentials, query/fragment smuggling) but deliberately does NOT enforce
 * a host allowlist — consistent with the platform's standing
 * internal-use-only decision that defers SSRF controls (S-5/S-6/S-11) to
 * the cloud step. It is a NEW reason those controls are mandatory there:
 * an operator-typed URL is fetched every 30 s by every replica.
 */
public record StaticPodDeclaration(String podName, String baseUrl) {

    /**
     * DNS-1123-ish: what a real Kubernetes Pod or Docker container is
     * actually named. Permissive enough for both substrates (underscores
     * and dots are legal container-name characters), strict enough that
     * whitespace, control characters and path separators can never reach
     * the registry key.
     */
    private static final Pattern POD_NAME =
            Pattern.compile("[a-zA-Z0-9]([a-zA-Z0-9._-]*[a-zA-Z0-9])?");

    private static final int MAX_POD_NAME_LENGTH = 253;
    private static final int MAX_BASE_URL_LENGTH = 512;

    /**
     * Validates and normalises. Throws {@link IllegalArgumentException}
     * with an operator-readable message naming the offending field — the
     * caller maps that to {@code 400 INVALID_REQUEST}.
     */
    public static StaticPodDeclaration of(String podName, String baseUrl) {
        String name = podName == null ? "" : podName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("podName is required");
        }
        if (name.length() > MAX_POD_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "podName must be at most " + MAX_POD_NAME_LENGTH + " characters; got " + name.length());
        }
        if (!POD_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "podName must be alphanumeric with '.', '-' or '_' inside (a Kubernetes Pod / "
                    + "Docker container name); got '" + name + "'");
        }

        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        if (url.length() > MAX_BASE_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "baseUrl must be at most " + MAX_BASE_URL_LENGTH + " characters; got " + url.length());
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl is not a valid URL: " + e.getReason());
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException(
                    "baseUrl must be absolute, e.g. http://worker-1.workers:8080; got '" + url + "'");
        }
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(
                    "baseUrl scheme must be http or https; got '" + scheme + "'");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl must include a host; got '" + url + "'");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must not embed credentials — the control plane does not "
                    + "authenticate to workers this way");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must not carry a query string or fragment; it is a base address, "
                    + "not a request");
        }
        return new StaticPodDeclaration(name, stripTrailingSlash(url));
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
