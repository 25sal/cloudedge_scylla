package de.hpi.bpt.scylla.plugin.resourcecost;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.jdom2.Element;

import de.hpi.bpt.scylla.exception.ScyllaValidationException;
import de.hpi.bpt.scylla.logger.DebugLogger;
import de.hpi.bpt.scylla.model.configuration.SimulationConfiguration;
import de.hpi.bpt.scylla.model.process.ProcessModel;
import de.hpi.bpt.scylla.plugin_type.parser.SimulationConfigurationParserPluggable;

/**
 * Reads the workload of tasks from {@code <attribute key="numInstructions" value="2000"/>} children
 * (keys: numInstructions, readOps, writeOps, txMB, rxMB).
 */
public class ResourceCostSCParserPlugin extends SimulationConfigurationParserPluggable {

    @Override
    public String getName() {
        return ResourceCostPluginUtils.PLUGIN_NAME;
    }

    @Override
    public Map<String, Object> parse(SimulationConfiguration simulationInput, Element sim)
            throws ScyllaValidationException {

        ProcessModel processModel = simulationInput.getProcessModel();
        Map<Integer, Map<String, Double>> workloads = new HashMap<Integer, Map<String, Double>>();

        for (Element el : sim.getChildren()) {
            if (!ResourceCostPluginUtils.isTaskElement(el)) {
                continue;
            }
            Iterable<Element> attributes = el.getChildren("attribute", el.getNamespace());
            if (!attributes.iterator().hasNext()) {
                continue;
            }
            String identifier = el.getAttributeValue("id");
            Integer nodeId = identifier == null ? null : processModel.getIdentifiersToNodeIds().get(identifier);
            if (nodeId == null) {
                DebugLogger.log("Workload definition for task '" + identifier
                        + "', but not available in process, skip.");
                continue;
            }

            Map<String, Double> workload = new HashMap<String, Double>();
            for (Element attribute : attributes) {
                String key = attribute.getAttributeValue("key");
                if (!ResourceCostPluginUtils.WORKLOAD_KEYS.contains(key)) {
                    throw new ScyllaValidationException("Unknown workload attribute '" + key + "' of task "
                            + identifier + ". Known: " + ResourceCostPluginUtils.WORKLOAD_KEYS + ".");
                }
                workload.put(key, ResourceCostPluginUtils.parsePositive(attribute.getAttributeValue("value"), true,
                        key + " of task " + identifier));
            }
            workloads.put(nodeId, workload);
            DebugLogger.log(ResourceCostPluginUtils.LOG_PREFIX + "workload of task '" + identifier + "': "
                    + new TreeMap<String, Double>(workload));
        }

        Map<String, Object> extensionAttributes = new HashMap<String, Object>();
        extensionAttributes.put(ResourceCostPluginUtils.WORKLOADS_KEY, workloads);
        return extensionAttributes;
    }
}
