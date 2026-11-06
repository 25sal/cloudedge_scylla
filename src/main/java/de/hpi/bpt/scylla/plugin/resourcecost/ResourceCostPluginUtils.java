package de.hpi.bpt.scylla.plugin.resourcecost;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jdom2.Element;

import de.hpi.bpt.scylla.exception.ScyllaValidationException;
import de.hpi.bpt.scylla.model.configuration.SimulationConfiguration;
import de.hpi.bpt.scylla.simulation.ResourceObjectTuple;
import de.hpi.bpt.scylla.simulation.SimulationModel;
import de.hpi.bpt.scylla.simulation.event.ScyllaEvent;
import de.hpi.bpt.scylla.simulation.utils.DateTimeUtils;

final class ResourceCostPluginUtils {

    static final String PLUGIN_NAME = "resourceCost";
    static final String LOG_PREFIX = "[resourceCost] ";
    static final String PROFILES_KEY = "profiles";
    static final String EXPONENT_KEY = "exponent";
    static final String WORKLOADS_KEY = "workloads";

    /** Attribute of the global configuration root holding the softmax exponent p. */
    static final String EXPONENT_ATTRIBUTE = "softmaxExponent";
    static final double DEFAULT_EXPONENT = 1.0;

    // task workload
    static final String INSTRUCTIONS = "numInstructions";
    static final String READ_OPS = "readOps";
    static final String WRITE_OPS = "writeOps";
    static final String TX_MB = "txMB";
    static final String RX_MB = "rxMB";
    static final List<String> WORKLOAD_KEYS = Arrays.asList(INSTRUCTIONS, READ_OPS, WRITE_OPS, TX_MB, RX_MB);

    // resource hardware
    static final String CPU_SPEED = "cpuSpeed";
    static final String IDLE_FACTOR = "idleFactor";
    static final String IO_READ = "ioRead";
    static final String IO_WRITE = "ioWrite";
    static final String TX_SPEED = "txSpeed";
    static final String RX_SPEED = "rxSpeed";
    static final List<String> SPEED_KEYS = Arrays.asList(CPU_SPEED, IO_READ, IO_WRITE, TX_SPEED, RX_SPEED);

    private ResourceCostPluginUtils() {
    }

    /** @return e.g. [client_#0+server_#1] */
    static String describe(ResourceObjectTuple tuple) {
        return tuple.getResourceObjects().stream().map(each -> each.getResourceType() + "_" + each.getId()).sorted()
                .collect(Collectors.joining("+", "[", "]"));
    }

    /** @return simulated date time, process instance and task of the event */
    static String context(SimulationModel model, ScyllaEvent event) {
        return DateTimeUtils.getDateTime(model.presentTime()) + " " + event.getProcessInstance().getName()
                + " task '" + event.getDisplayName() + "'";
    }

    static boolean isTaskElement(Element el) {
        String name = el.getName();
        return name.equals("task") || name.endsWith("Task");
    }

    /** @return the value, which must be a finite number above (or, if allowed, equal to) zero */
    static double parsePositive(String value, boolean zeroAllowed, String context) throws ScyllaValidationException {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isNaN(number) && !Double.isInfinite(number) && (zeroAllowed ? number >= 0 : number > 0)) {
                return number;
            }
        }
        catch (NumberFormatException e) {
            // reported below
        }
        throw new ScyllaValidationException(
                context + " must be a " + (zeroAllowed ? "non-negative" : "positive") + " number, but is " + value + ".");
    }

    /** @return a finite number in [0, 1) */
    static double parseIdleFactor(String value, String context) throws ScyllaValidationException {
        try {
            double number = Double.parseDouble(value);
            if (number >= 0 && number < 1) {
                return number;
            }
        }
        catch (NumberFormatException e) {
            // reported below
        }
        throw new ScyllaValidationException(context + " must be a number in [0, 1), but is " + value + ".");
    }

    /** @return a finite number of at least 1 */
    static double parseExponent(String value, String context) throws ScyllaValidationException {
        try {
            double number = Double.parseDouble(value);
            if (number >= 1 && !Double.isInfinite(number)) {
                return number;
            }
        }
        catch (NumberFormatException e) {
            // reported below
        }
        throw new ScyllaValidationException(context + " must be a number of at least 1, but is " + value + ".");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Double> getWorkload(ScyllaEvent event) {
        SimulationConfiguration configuration = event.getSimulationComponents().getSimulationConfiguration();
        Map<Integer, Map<String, Double>> workloads = (Map<Integer, Map<String, Double>>) configuration
                .getExtensionValue(PLUGIN_NAME, WORKLOADS_KEY);
        return workloads == null ? Collections.<String, Double>emptyMap()
                : workloads.getOrDefault(event.getNodeId(), Collections.<String, Double>emptyMap());
    }
}
