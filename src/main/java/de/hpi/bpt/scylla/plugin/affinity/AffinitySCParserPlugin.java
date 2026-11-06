package de.hpi.bpt.scylla.plugin.affinity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jdom2.Element;

import de.hpi.bpt.scylla.exception.ScyllaValidationException;
import de.hpi.bpt.scylla.logger.DebugLogger;
import de.hpi.bpt.scylla.model.configuration.ResourceReference;
import de.hpi.bpt.scylla.model.configuration.SimulationConfiguration;
import de.hpi.bpt.scylla.model.global.resource.DynamicResource;
import de.hpi.bpt.scylla.model.global.resource.Resource;
import de.hpi.bpt.scylla.model.process.ProcessModel;
import de.hpi.bpt.scylla.plugin_type.parser.SimulationConfigurationParserPluggable;

/**
 * Parses {@code <affinity>} elements of tasks:
 * 
 * <pre>
 * &lt;bsim:task id="T"&gt;
 *   &lt;bsim:affinity&gt;
 *     &lt;bsim:resource id="server" instance="server1" relation="affinity|antiAffinity" mode="hard|soft" weight="2"/&gt;
 *     &lt;bsim:history task="OtherTask" relation="affinity|antiAffinity" mode="hard|soft" weight="2"/&gt;
 *   &lt;/bsim:affinity&gt;
 * &lt;/bsim:task&gt;
 * </pre>
 * 
 * A history element without {@code task} refers to the previously started task of the same process instance.
 * Defaults: relation affinity, mode soft, weight 1. The weight is ignored for hard rules.
 */
public class AffinitySCParserPlugin extends SimulationConfigurationParserPluggable {

    @Override
    public String getName() {
        return AffinityPluginUtils.PLUGIN_NAME;
    }

    @Override
    public Map<String, Object> parse(SimulationConfiguration simulationInput, Element sim)
            throws ScyllaValidationException {

        ProcessModel processModel = simulationInput.getProcessModel();
        Map<Integer, List<AffinityRule>> rulesPerNode = new HashMap<Integer, List<AffinityRule>>();

        for (Element el : sim.getChildren()) {
            String elementName = el.getName();
            if (!(elementName.equals("task") || elementName.endsWith("Task"))) {
                continue;
            }
            Element affinity = el.getChild("affinity", el.getNamespace());
            if (affinity == null) {
                continue;
            }
            String identifier = el.getAttributeValue("id");
            Integer nodeId = identifier == null ? null : processModel.getIdentifiersToNodeIds().get(identifier);
            if (nodeId == null) {
                DebugLogger.log("Affinity definition for task '" + identifier
                        + "', but not available in process, skip.");
                continue;
            }

            List<AffinityRule> rules = new ArrayList<AffinityRule>();
            for (Element ruleElement : affinity.getChildren()) {
                AffinityRule rule = parseRule(ruleElement, identifier, nodeId, simulationInput);
                DebugLogger.log(AffinityPluginUtils.LOG_PREFIX + "parsed rule of task '" + identifier + "': " + rule);
                rules.add(rule);
            }
            rulesPerNode.put(nodeId, rules);
        }

        Map<String, Object> extensionAttributes = new HashMap<String, Object>();
        extensionAttributes.put(AffinityPluginUtils.RULES_KEY, rulesPerNode);
        return extensionAttributes;
    }

    private AffinityRule parseRule(Element el, String taskId, int nodeId, SimulationConfiguration configuration)
            throws ScyllaValidationException {

        boolean hard = parseChoice(el, taskId, "mode", "soft", "hard");
        boolean anti = parseChoice(el, taskId, "relation", "affinity", "antiAffinity");
        double weight = parseWeight(el, taskId);

        switch (el.getName()) {
            case "resource":
                return parseResourceRule(el, taskId, nodeId, configuration, hard, anti, weight);
            case "history":
                return parseHistoryRule(el, taskId, configuration.getProcessModel(), hard, anti, weight);
            default:
                throw new ScyllaValidationException(
                        "Unknown affinity element '" + el.getName() + "' for task " + taskId + ".");
        }
    }

    private AffinityRule parseResourceRule(Element el, String taskId, int nodeId,
            SimulationConfiguration configuration, boolean hard, boolean anti, double weight)
                    throws ScyllaValidationException {

        String resourceType = el.getAttributeValue("id");
        String instanceId = el.getAttributeValue("instance");

        Resource resource = simulationEnvironment.getGlobalConfiguration().getResources().get(resourceType);
        if (resource == null) {
            throw new ScyllaValidationException(
                    "Affinity of task " + taskId + " refers to unknown resource " + resourceType + ".");
        }
        if (instanceId != null && resource instanceof DynamicResource
                && !((DynamicResource) resource).getResourceInstances().containsKey(instanceId)) {
            throw new ScyllaValidationException(
                    "Affinity of task " + taskId + " refers to unknown instance " + instanceId + " of " + resourceType + ".");
        }

        // a positive rule on a type the task never uses could never be satisfied and would block the task forever
        if (!anti) {
            boolean required = false;
            for (ResourceReference reference : configuration.getResourceReferenceSet(nodeId)) {
                required |= reference.getResourceId().equals(resourceType);
            }
            if (!required) {
                throw new ScyllaValidationException("Affinity of task " + taskId + " requires resource "
                        + resourceType + ", which the task does not use.");
            }
        }
        return AffinityRule.forResource(hard, anti, weight, resourceType, instanceId);
    }

    private AffinityRule parseHistoryRule(Element el, String taskId, ProcessModel processModel, boolean hard,
            boolean anti, double weight) throws ScyllaValidationException {

        String referencedTask = el.getAttributeValue("task");
        Integer referencedNodeId = null;
        if (referencedTask != null) {
            referencedNodeId = processModel.getIdentifiersToNodeIds().get(referencedTask);
            if (referencedNodeId == null) {
                throw new ScyllaValidationException(
                        "Affinity of task " + taskId + " refers to unknown task " + referencedTask + ".");
            }
        }
        return AffinityRule.forHistory(hard, anti, weight, referencedNodeId, referencedTask);
    }

    /** @return true if the attribute has the second option, false if it has the first one or is missing */
    private static boolean parseChoice(Element el, String taskId, String attribute, String first, String second)
            throws ScyllaValidationException {
        String value = el.getAttributeValue(attribute);
        if (value == null || value.equalsIgnoreCase(first)) {
            return false;
        }
        if (value.equalsIgnoreCase(second)) {
            return true;
        }
        throw new ScyllaValidationException("Affinity of task " + taskId + ": " + attribute + " must be " + first
                + " or " + second + ", but is " + value + ".");
    }

    private static double parseWeight(Element el, String taskId) throws ScyllaValidationException {
        String value = el.getAttributeValue("weight");
        if (value == null) {
            return 1.0;
        }
        try {
            double weight = Double.parseDouble(value);
            if (weight > 0 && !Double.isInfinite(weight)) {
                return weight;
            }
        }
        catch (NumberFormatException e) {
            // reported below
        }
        throw new ScyllaValidationException(
                "Affinity of task " + taskId + ": weight must be a positive number, but is " + value + ".");
    }
}
