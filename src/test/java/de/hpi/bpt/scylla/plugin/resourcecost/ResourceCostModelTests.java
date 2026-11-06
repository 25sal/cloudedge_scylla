package de.hpi.bpt.scylla.plugin.resourcecost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.hpi.bpt.scylla.exception.ScyllaRuntimeException;
import de.hpi.bpt.scylla.simulation.ResourceObject;

public class ResourceCostModelTests {

    private static final double EPS = 1e-9;

    private final ResourceObject node = new ResourceObject("node", "n1");
    private final HardwareProfiles profiles = new HardwareProfiles();

    // T_cpu = 10, T_io = 2 + 2, T_net = 2 + 1
    private final Map<String, Double> workload = map("numInstructions", 1000, "readOps", 20, "writeOps", 10, "txMB",
            40, "rxMB", 20);

    private static Map<String, Double> map(Object... keysAndValues) {
        Map<String, Double> map = new HashMap<String, Double>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], ((Number) keysAndValues[i + 1]).doubleValue());
        }
        return map;
    }

    private Map<String, Double> hardware(double idleFactor) {
        return map("cpuSpeed", 100, "idleFactor", idleFactor, "ioRead", 10, "ioWrite", 5, "txSpeed", 20, "rxSpeed", 20);
    }

    private double seconds(double exponent, List<ResourceObject> resources) throws ScyllaRuntimeException {
        return ResourceCostBasedTaskDurationPlugin.computeSeconds("task", workload, resources, profiles, exponent);
    }

    @Test
    public void exponentOneAddsComponents() throws Exception {
        profiles.setDefaults("node", hardware(0));
        assertEquals(17, seconds(1, Arrays.asList(node)), EPS);
    }

    @Test
    public void exponentTwoIsEuclideanNorm() throws Exception {
        profiles.setDefaults("node", hardware(0));
        assertEquals(Math.sqrt(100 + 16 + 9), seconds(2, Arrays.asList(node)), EPS);
    }

    @Test
    public void largeExponentApproachesMaximumWithoutOverflow() throws Exception {
        profiles.setDefaults("node", hardware(0));
        double result = seconds(1000, Arrays.asList(node));
        assertTrue(result >= 10 && result < 10.01, "was " + result);
    }

    @Test
    public void idleFactorScalesCpuSpeed() throws Exception {
        profiles.setDefaults("node", hardware(0.5)); // effective speed 50 -> T_cpu = 20
        assertEquals(Math.sqrt(400 + 16 + 9), seconds(2, Arrays.asList(node)), EPS);
    }

    @Test
    public void instanceValuesOverrideTypeDefaults() throws Exception {
        profiles.setDefaults("node", hardware(0));
        Map<String, Double> slow = hardware(0);
        slow.put("cpuSpeed", 50.0);
        profiles.setInstance("node", "n1", slow);
        assertEquals(20 + 4 + 3, seconds(1, Arrays.asList(node)), EPS);
    }

    @Test
    public void slowestAssignedResourceIsBottleneck() throws Exception {
        profiles.setDefaults("node", hardware(0));
        ResourceObject slow = new ResourceObject("slow", "s1");
        profiles.setDefaults("slow", map("cpuSpeed", 50, "ioRead", 10, "ioWrite", 5, "txSpeed", 20, "rxSpeed", 20));
        assertEquals(27, seconds(1, Arrays.asList(node, slow)), EPS);
    }

    @Test
    public void taskWithoutWorkloadHasNoServiceTime() throws Exception {
        assertEquals(0, ResourceCostBasedTaskDurationPlugin.computeSeconds("task", Collections.<String, Double>emptyMap(),
                Arrays.asList(node), profiles, 2), EPS);
    }

    @Test
    public void missingSpeedIsReported() {
        profiles.setDefaults("node", map("cpuSpeed", 100));
        assertThrows(ScyllaRuntimeException.class, () -> seconds(2, Arrays.asList(node)));
    }
}
