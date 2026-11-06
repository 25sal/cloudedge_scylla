package de.hpi.bpt.scylla.plugin.resourcecost;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import de.hpi.bpt.scylla.simulation.ResourceObject;

/**
 * Hardware attributes of resource instances (speeds and CPU idle factor), as read from the global configuration.
 * Instance values override the defaults given on the resource type.
 */
final class HardwareProfiles {

    private final Map<String, Map<String, Double>> defaultsPerType = new HashMap<String, Map<String, Double>>();
    private final Map<String, Map<String, Map<String, Double>>> specsPerInstance = new HashMap<String, Map<String, Map<String, Double>>>();

    void setDefaults(String resourceType, Map<String, Double> specs) {
        defaultsPerType.put(resourceType, specs);
    }

    /** @param specs complete specs of the instance, i.e. already merged with the defaults of its type */
    void setInstance(String resourceType, String instanceId, Map<String, Double> specs) {
        specsPerInstance.computeIfAbsent(resourceType, each -> new HashMap<String, Map<String, Double>>())
                .put(instanceId, specs);
    }

    /** @return the value, or null if the resource does not define it */
    Double value(ResourceObject resource, String key) {
        Map<String, Double> specs = specsPerInstance
                .getOrDefault(resource.getResourceType(), Collections.<String, Map<String, Double>>emptyMap())
                .get(resource.getId());
        if (specs == null) {
            specs = defaultsPerType.getOrDefault(resource.getResourceType(), Collections.<String, Double>emptyMap());
        }
        Double value = specs.get(key);
        if (value == null && key.equals(ResourceCostPluginUtils.CPU_SPEED) && resource.getCost() > 0) {
            // configurations written for the cost attribute used it as CPU speed (instructions per second)
            return resource.getCost();
        }
        return value;
    }

    /** @return cpuSpeed * (1 - idleFactor), or null if the resource does not define a CPU speed */
    Double effectiveCpuSpeed(ResourceObject resource) {
        Double speed = value(resource, ResourceCostPluginUtils.CPU_SPEED);
        if (speed == null) {
            return null;
        }
        Double idle = value(resource, ResourceCostPluginUtils.IDLE_FACTOR);
        return speed * (1 - (idle == null ? 0 : idle));
    }
}
