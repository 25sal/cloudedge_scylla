package de.hpi.bpt.scylla.plugin.affinity;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.hpi.bpt.scylla.simulation.ProcessInstance;
import de.hpi.bpt.scylla.simulation.ResourceObject;

/**
 * Resources that executed the tasks of one process instance (sub-processes have their own history).
 */
final class AffinityHistory {

    // weak keys: histories vanish with their process instance, so no reset between simulation runs is needed
    private static final Map<ProcessInstance, AffinityHistory> HISTORIES = new WeakHashMap<ProcessInstance, AffinityHistory>();
    private static final AffinityHistory EMPTY = new AffinityHistory();

    private final Map<Integer, Set<ResourceObject>> lastResourcesPerNode = new HashMap<Integer, Set<ResourceObject>>();
    private Set<ResourceObject> previous = Collections.emptySet();

    static AffinityHistory of(ProcessInstance processInstance) {
        return HISTORIES.computeIfAbsent(processInstance, each -> new AffinityHistory());
    }

    /** Read-only access that does not create a history. */
    static AffinityHistory find(ProcessInstance processInstance) {
        AffinityHistory history = HISTORIES.get(processInstance);
        return history == null ? EMPTY : history;
    }

    void record(int nodeId, Set<ResourceObject> resources) {
        Set<ResourceObject> copy = new HashSet<ResourceObject>(resources);
        lastResourcesPerNode.put(nodeId, copy);
        previous = copy;
    }

    Set<ResourceObject> resourcesOf(int nodeId) {
        return lastResourcesPerNode.getOrDefault(nodeId, Collections.<ResourceObject>emptySet());
    }

    Set<ResourceObject> previous() {
        return previous;
    }
}
