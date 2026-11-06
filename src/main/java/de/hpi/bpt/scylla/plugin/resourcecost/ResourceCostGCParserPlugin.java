package de.hpi.bpt.scylla.plugin.resourcecost;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.jdom2.Element;
import org.jdom2.Namespace;

import de.hpi.bpt.scylla.exception.ScyllaValidationException;
import de.hpi.bpt.scylla.logger.DebugLogger;
import de.hpi.bpt.scylla.model.global.GlobalConfiguration;
import de.hpi.bpt.scylla.plugin_type.parser.GlobalConfigurationParserPluggable;

/**
 * Reads hardware attributes (cpuSpeed, idleFactor, ioRead, ioWrite, txSpeed, rxSpeed) from the attributes of
 * {@code <dynamicResource>} (defaults for all its instances) and {@code <instance>} elements, and the softmax
 * exponent p from the {@code softmaxExponent} attribute of the global configuration (default 1).
 */
public class ResourceCostGCParserPlugin extends GlobalConfigurationParserPluggable {

    @Override
    public String getName() {
        return ResourceCostPluginUtils.PLUGIN_NAME;
    }

    @Override
    public Map<String, Object> parse(GlobalConfiguration globalConfiguration, Element root)
            throws ScyllaValidationException {

        HardwareProfiles profiles = new HardwareProfiles();
        Namespace namespace = root.getNamespace();
        Element resourceData = root.getChild("resourceData", namespace);

        if (resourceData != null) {
            for (Element resource : resourceData.getChildren("dynamicResource", namespace)) {
                String type = resource.getAttributeValue("id");
                Map<String, Double> defaults = readSpeeds(resource, "resource " + type);
                profiles.setDefaults(type, defaults);
                DebugLogger.log(ResourceCostPluginUtils.LOG_PREFIX + "resource type '" + type + "': "
                        + new TreeMap<String, Double>(defaults));

                for (Element instance : resource.getChildren("instance", namespace)) {
                    String name = instance.getAttributeValue("name");
                    Map<String, Double> specs = new HashMap<String, Double>(defaults);
                    specs.putAll(readSpeeds(instance, "instance " + name + " of resource " + type));
                    profiles.setInstance(type, name, specs);
                    DebugLogger.log(ResourceCostPluginUtils.LOG_PREFIX + "instance '" + name + "' of '" + type + "': "
                            + new TreeMap<String, Double>(specs));
                }
            }
        }

        Map<String, Object> extensionAttributes = new HashMap<String, Object>();
        extensionAttributes.put(ResourceCostPluginUtils.PROFILES_KEY, profiles);

        String exponent = root.getAttributeValue(ResourceCostPluginUtils.EXPONENT_ATTRIBUTE);
        double exponentValue = exponent == null ? ResourceCostPluginUtils.DEFAULT_EXPONENT
                : ResourceCostPluginUtils.parseExponent(exponent, ResourceCostPluginUtils.EXPONENT_ATTRIBUTE);
        extensionAttributes.put(ResourceCostPluginUtils.EXPONENT_KEY, exponentValue);
        DebugLogger.log(ResourceCostPluginUtils.LOG_PREFIX + "softmax exponent p = " + exponentValue
                + (exponent == null ? " (default)" : ""));
        return extensionAttributes;
    }

    private static Map<String, Double> readSpeeds(Element element, String context) throws ScyllaValidationException {
        Map<String, Double> specs = new HashMap<String, Double>();
        for (String key : ResourceCostPluginUtils.SPEED_KEYS) {
            String value = element.getAttributeValue(key);
            if (value != null) {
                specs.put(key, ResourceCostPluginUtils.parsePositive(value, false, key + " of " + context));
            }
        }
        String idle = element.getAttributeValue(ResourceCostPluginUtils.IDLE_FACTOR);
        if (idle != null) {
            specs.put(ResourceCostPluginUtils.IDLE_FACTOR, ResourceCostPluginUtils.parseIdleFactor(idle,
                    ResourceCostPluginUtils.IDLE_FACTOR + " of " + context));
        }
        return specs;
    }
}
