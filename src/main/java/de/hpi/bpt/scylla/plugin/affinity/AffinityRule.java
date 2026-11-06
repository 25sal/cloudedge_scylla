package de.hpi.bpt.scylla.plugin.affinity;

import java.util.Set;

import de.hpi.bpt.scylla.simulation.ResourceObject;
import de.hpi.bpt.scylla.simulation.ResourceObjectTuple;

/**
 * Affinity or anti-affinity of a task towards a resource (static rule) or towards the resources that executed an
 * earlier task of the same process instance (history rule).
 */
final class AffinityRule {

    private final boolean hard;
    private final boolean anti;
    private final double weight;

    private final String resourceType; // static rule if not null
    private final String instanceId; // optional restriction of a static rule to one instance

    private final Integer referencedNodeId; // history rule: null refers to the previously started task

    private final String description;

    private AffinityRule(boolean hard, boolean anti, double weight, String resourceType, String instanceId,
            Integer referencedNodeId, String description) {
        this.hard = hard;
        this.anti = anti;
        this.weight = weight;
        this.resourceType = resourceType;
        this.instanceId = instanceId;
        this.referencedNodeId = referencedNodeId;
        this.description = description;
    }

    static AffinityRule forResource(boolean hard, boolean anti, double weight, String resourceType,
            String instanceId) {
        String target = "resource " + resourceType + (instanceId == null ? "" : " instance " + instanceId);
        return new AffinityRule(hard, anti, weight, resourceType, instanceId, null,
                describe(hard, anti, weight, target));
    }

    /** @param referencedTaskId identifier of the referenced task, null for the previously started task */
    static AffinityRule forHistory(boolean hard, boolean anti, double weight, Integer referencedNodeId,
            String referencedTaskId) {
        String target = referencedTaskId == null ? "resources of the previously started task"
                : "resources of task '" + referencedTaskId + "'";
        return new AffinityRule(hard, anti, weight, null, null, referencedNodeId,
                describe(hard, anti, weight, target));
    }

    private static String describe(boolean hard, boolean anti, double weight, String target) {
        return (hard ? "HARD " : "SOFT ") + (anti ? "anti-affinity" : "affinity") + " to " + target
                + (hard ? "" : " (weight " + weight + ")");
    }

    @Override
    public String toString() {
        return description;
    }

    boolean isHard() {
        return hard;
    }

    double getWeight() {
        return weight;
    }

    /** A history rule applies once the task it refers to has started in the process instance. */
    boolean appliesTo(AffinityHistory history) {
        return resourceType != null || !reference(history).isEmpty();
    }

    private Set<ResourceObject> reference(AffinityHistory history) {
        return referencedNodeId == null ? history.previous() : history.resourcesOf(referencedNodeId);
    }

    /**
     * @return null if the rule does not apply (history rule whose reference task has not run yet), otherwise whether
     *         the tuple satisfies the rule
     */
    Boolean isSatisfiedBy(ResourceObjectTuple tuple, AffinityHistory history) {
        Set<ResourceObject> used = tuple.getResourceObjects();
        boolean matches;
        if (resourceType != null) {
            matches = used.stream().anyMatch(each -> each.getResourceType().equals(resourceType)
                    && (instanceId == null || each.getId().equals(instanceId)));
        }
        else {
            Set<ResourceObject> reference = reference(history);
            if (reference.isEmpty()) {
                return null;
            }
            matches = used.stream().anyMatch(reference::contains);
        }
        return matches != anti;
    }
}
