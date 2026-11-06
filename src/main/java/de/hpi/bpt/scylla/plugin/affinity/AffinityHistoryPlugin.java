package de.hpi.bpt.scylla.plugin.affinity;

import de.hpi.bpt.scylla.logger.DebugLogger;
import de.hpi.bpt.scylla.plugin_type.simulation.event.TaskBeginEventPluggable;
import de.hpi.bpt.scylla.simulation.ProcessInstance;
import de.hpi.bpt.scylla.simulation.ResourceObjectTuple;
import de.hpi.bpt.scylla.simulation.SimulationModel;
import de.hpi.bpt.scylla.simulation.event.TaskBeginEvent;

/**
 * Records which resources executed each task, so that later tasks of the process instance can express affinity to them.
 */
public class AffinityHistoryPlugin extends TaskBeginEventPluggable {

    @Override
    public String getName() {
        return AffinityPluginUtils.PLUGIN_NAME;
    }

    @Override
    public void eventRoutine(TaskBeginEvent event, ProcessInstance processInstance) {
        ResourceObjectTuple tuple = processInstance.getAssignedResources().get(event.getSource());
        if (tuple == null || tuple.getResourceObjects().isEmpty()) {
            return;
        }
        AffinityHistory.of(processInstance).record(event.getNodeId(), tuple.getResourceObjects());
        DebugLogger.log(AffinityPluginUtils.LOG_PREFIX + AffinityPluginUtils.context((SimulationModel) event.getModel(), event)
                + ": recorded in history, executed by " + AffinityPluginUtils.describe(tuple));
    }
}
