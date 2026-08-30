package com.perf.regionalorchestrator.http;

import com.perf.regionalorchestrator.provision.PodNames;
import com.perf.regionalorchestrator.provision.PodProvisioner;
import com.perf.regionalorchestrator.provision.PodSpec;
import com.perf.regionalorchestrator.provision.ProvisionResult;
import com.perf.regionalorchestrator.provision.ProvisionedPod;
import com.perf.regionalorchestrator.provision.RegionalProperties;
import com.perf.regionalorchestrator.provision.WorkerState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The {@link PodProvisioner} over HTTP, one route per verb, for the global
 * orchestrator's {@code RegionalPodProvisioner}. A spec naming a region other
 * than this deployment's answers {@code 400 REGION_MISMATCH}.
 */
@RestController
@RequestMapping("/api/v1")
public class PodsController {

    private final PodProvisioner provisioner;
    private final RegionalProperties region;

    public PodsController(PodProvisioner provisioner, RegionalProperties region) {
        this.provisioner = provisioner;
        this.region = region;
    }

    /**
     * Pod lifecycle facts the global reads: {@code running} is the kubelet's
     * process-up, {@code ready} is the readiness probe (Tomcat answering),
     * {@code dead}/{@code reason} the kubelet's verdict when it is over.
     */
    public record PodState(String podName, boolean exists, boolean running, boolean ready,
                           boolean dead, String phase, String reason, Integer exitCode) {}

    @PostMapping("/pods")
    public ResponseEntity<?> create(@RequestBody PodSpec spec) {
        if (!region.region().equals(spec.region())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", "REGION_MISMATCH",
                    "message", "this regional orchestrator serves region '" + region.region()
                            + "', not '" + spec.region() + "'"));
        }
        ProvisionResult result = provisioner.createAndStart(spec);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/pods")
    public List<ProvisionedPod> list(@RequestParam String groupId,
                                     @RequestParam(required = false) String region) {
        return provisioner.listFor(groupId, region);
    }

    @GetMapping("/pods/{podName}")
    public ResponseEntity<?> get(@PathVariable String podName) {
        if (!PodNames.isValid(podName)) return invalidName(podName);
        return ResponseEntity.ok(provisioner.workerState(podName)
                .map(w -> new PodState(podName, true, "Running".equals(w.phase()) && !w.dead(),
                        w.ready(), w.dead(), w.phase(), w.reason(), w.exitCode()))
                .orElse(new PodState(podName, false, false, false, false, null, null, null)));
    }

    @DeleteMapping("/pods/{podName}")
    public ResponseEntity<?> delete(@PathVariable String podName) {
        if (!PodNames.isValid(podName)) return invalidName(podName);
        provisioner.stopAndRemove(podName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pods/{podName}/stop")
    public ResponseEntity<?> stop(@PathVariable String podName) {
        if (!PodNames.isValid(podName)) return invalidName(podName);
        provisioner.stop(podName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pods/{podName}/start")
    public ResponseEntity<?> start(@PathVariable String podName) {
        if (!PodNames.isValid(podName)) return invalidName(podName);
        provisioner.start(podName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pods/{podName}/restart")
    public ResponseEntity<?> restart(@PathVariable String podName) {
        if (!PodNames.isValid(podName)) return invalidName(podName);
        provisioner.restart(podName);
        return ResponseEntity.noContent().build();
    }

    /** Liveness of every managed worker, straight from the Pod list. */
    @GetMapping("/workers")
    public List<WorkerState> workers() {
        return provisioner.listWorkers();
    }

    @GetMapping("/image")
    public Map<String, String> image() {
        return Map.of("imageDigest", provisioner.currentImageDigest());
    }

    private static ResponseEntity<Map<String, String>> invalidName(String podName) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "INVALID_POD_NAME",
                "message", "podName must be a DNS-1123 label; got '" + podName + "'"));
    }
}
