package de.hpi.bpt.scylla.plugin.affinity;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import de.hpi.bpt.scylla.model.configuration.SimulationConfiguration;
import de.hpi.bpt.scylla.simulation.ResourceObject;
import de.hpi.bpt.scylla.simulation.ResourceObjectTuple;
import de.hpi.bpt.scylla.simulation.SimulationModel;
import de.hpi.bpt.scylla.simulation.event.ScyllaEvent;
import de.hpi.bpt.scylla.simulation.utils.DateTimeUtils;

final class AffinityPluginUtils {

    static final String PLUGIN_NAME = "affinity";
    static final String RULES_KEY = "rules";
    static final String LOG_PREFIX = "[affinity] ";

    private AffinityPluginUtils() {
    }

    /** @return e.g. [server_#0+client_#1] */
    static String describe(Collection<ResourceObject> resources) {
        return resources.stream().map(each -> each.getResourceType() + "_" + each.getId()).sorted()
                .collect(Collectors.joining("+", "[", "]"));
    }

    static String describe(ResourceObjectTuple tuple) {
        return describe(tuple.getResourceObjects());
    }

    static String describeAll(List<ResourceObjectTuple> tuples) {
        return tuples.isEmpty() ? "(none)"
                : tuples.stream().map(AffinityPluginUtils::describe).collect(Collectors.joining(" > "));
    }

    /** @return simulated date time, process instance and task of the event */
    static String context(SimulationModel model, ScyllaEvent event) {
        return DateTimeUtils.getDateTime(model.presentTime()) + " " + event.getProcessInstance().getName()
                + " task '" + event.getDisplayName() + "'";
    }

    @SuppressWarnings("unchecked")
    static List<AffinityRule> getRules(ScyllaEvent event) {
        SimulationConfiguration configuration = event.getSimulationComponents().getSimulationConfiguration();
        Map<Integer, List<AffinityRule>> rules = (Map<Integer, List<AffinityRule>>) configuration
                .getExtensionValue(PLUGIN_NAME, RULES_KEY);
        if (rules == null) {
            return Collections.emptyList();
        }
        return rules.getOrDefault(event.getNodeId(), Collections.<AffinityRule>emptyList());
    }
}
