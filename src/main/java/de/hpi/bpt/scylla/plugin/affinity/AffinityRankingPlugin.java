package de.hpi.bpt.scylla.plugin.affinity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import de.hpi.bpt.scylla.logger.DebugLogger;
import de.hpi.bpt.scylla.plugin_type.simulation.resource.ResourceTupleRankingPluggable;
import de.hpi.bpt.scylla.simulation.ResourceObjectTuple;
import de.hpi.bpt.scylla.simulation.SimulationModel;
import de.hpi.bpt.scylla.simulation.event.ScyllaEvent;

/**
 * Drops candidates that violate a hard rule and orders the rest by the summed weight of satisfied soft rules.
 * Candidates with equal scores keep their default order.
 */
public class AffinityRankingPlugin extends ResourceTupleRankingPluggable {

    @Override
    public String getName() {
        return AffinityPluginUtils.PLUGIN_NAME;
    }

    @Override
    public List<ResourceObjectTuple> rank(SimulationModel model, ScyllaEvent event,
            List<ResourceObjectTuple> candidates) {
        List<AffinityRule> rules = AffinityPluginUtils.getRules(event);
        if (rules.isEmpty()) {
            return candidates;
        }
        String prefix = AffinityPluginUtils.LOG_PREFIX;
        DebugLogger.log(prefix + AffinityPluginUtils.context(model, event) + ": ranking " + candidates.size()
                + " candidate(s) with " + rules.size() + " rule(s)");
        List<ResourceObjectTuple> ranked = order(candidates, rules, AffinityHistory.find(event.getProcessInstance()),
                line -> DebugLogger.log(prefix + "  " + line));
        DebugLogger.log(prefix + "  result: " + (ranked.isEmpty() ? "no admissible resource, task has to wait"
                : AffinityPluginUtils.describeAll(ranked)));
        return ranked;
    }

    static List<ResourceObjectTuple> order(List<ResourceObjectTuple> candidates, List<AffinityRule> rules,
            AffinityHistory history) {
        return order(candidates, rules, history, line -> {
        });
    }

    static List<ResourceObjectTuple> order(List<ResourceObjectTuple> candidates, List<AffinityRule> rules,
            AffinityHistory history, Consumer<String> log) {
        Map<ResourceObjectTuple, Double> scores = new IdentityHashMap<ResourceObjectTuple, Double>();
        List<ResourceObjectTuple> admissible = new ArrayList<ResourceObjectTuple>();

        for (AffinityRule rule : rules) {
            if (!rule.appliesTo(history)) {
                log.accept("rule not applicable yet (referenced task has not started): " + rule);
            }
        }

        for (ResourceObjectTuple tuple : candidates) {
            double score = 0;
            AffinityRule rejectedBy = null;
            StringBuilder satisfiedSoft = new StringBuilder();
            for (AffinityRule rule : rules) {
                Boolean satisfied = rule.isSatisfiedBy(tuple, history);
                if (satisfied == null) {
                    continue;
                }
                if (rule.isHard()) {
                    if (!satisfied) {
                        rejectedBy = rule;
                        break;
                    }
                }
                else if (satisfied) {
                    score += rule.getWeight();
                    satisfiedSoft.append(" +").append(rule.getWeight());
                }
            }
            if (rejectedBy != null) {
                log.accept(AffinityPluginUtils.describe(tuple) + " rejected, violates " + rejectedBy);
            }
            else {
                admissible.add(tuple);
                scores.put(tuple, score);
                log.accept(AffinityPluginUtils.describe(tuple) + " admissible, score " + score + satisfiedSoft);
            }
        }

        // List.sort is stable
        admissible.sort(Comparator.comparingDouble((ResourceObjectTuple tuple) -> scores.get(tuple)).reversed());
        return admissible;
    }
}
