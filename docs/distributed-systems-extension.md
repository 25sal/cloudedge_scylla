# Distributed-systems extension: resource-aware service time and affinity scheduling

This extension lets Scylla simulate tasks that run on machines with different hardware (client/server style
systems). It adds two things on top of the unchanged simulator core:

1. **Dynamic execution time.** The duration of a task is computed from its workload and the hardware of the
   assigned resources, using a softmax aggregation of CPU, I/O and network service times.
2. **Affinity scheduling.** When a task is assigned to resources, hard and soft affinity / anti-affinity rules can
   prefer or forbid resources, based on fixed targets or on the history of the same process instance.

Everything lives in plugins (`plugin/resourcecost`, `plugin/affinity`). The core only gained one small extension
point.

## 1. Implementation

### 1.1 Core changes

| File | Change |
|---|---|
| `plugin_type/simulation/resource/ResourceTupleRankingPluggable` | New entry point. Plugins receive the candidate resource tuples of an event (already matched on resource types, amounts and timetables, default order first) and return them filtered and/or reordered. The first tuple is assigned; an empty list means the task waits. Implementations must be side-effect free, because ranking is also evaluated when only checking availability. |
| `simulation/QueueManager` | Calls the new entry point after the default last-access sort. When no tuple is chosen, the resources polled from the queues are now put back (new helper `returnToQueues`). |
| `META-INF/plugins/plugins_list` | Registers `de.hpi.bpt.scylla.plugin.affinity` (the `resourcecost` package was already listed). |

The `QueueManager` change also fixes a latent bug: the existing "no matching resource tuple" branch returned `null`
without returning the polled resources to their queues, which removed them from the simulation. Hard affinity rules
reach that path often, so both paths now use the same put-back.

`ResourceObject`, `DynamicResourceInstance`, the events and the parsers are **not** modified. All extra attributes
are parsed and kept inside the plugins.

### 1.2 Plugin `resourcecost` (dynamic execution time)

| Class | Role |
|---|---|
| `ResourceCostGCParserPlugin` | Global-configuration parser plugin. Reads hardware attributes from `<dynamicResource>` (defaults for all instances) and `<instance>` (overrides), and the exponent `softmaxExponent` from the root element. |
| `ResourceCostSCParserPlugin` | Simulation-configuration parser plugin. Reads the workload of tasks from `<attribute key=… value=…/>` children. |
| `HardwareProfiles` | Lookup of hardware values per resource instance (instance value, then type default). If `cpuSpeed` is missing it falls back to `defaultCost`. |
| `ResourceCostBasedTaskDurationPlugin` | Task-begin plugin. Computes the service time and reschedules the already planned terminate event through `DateTimeUtils.getTaskTerminationTime`, so resource timetables still apply. |
| `ResourceCostPluginUtils` | Constants, validation and shared helpers. |

Tasks without workload attributes are left untouched. A task with a workload whose assigned resources lack a needed
speed fails with an error naming the task and the missing attribute.

#### Formula

For one task with workload `numInstructions`, `readOps`, `writeOps`, `txMB`, `rxMB`:

```
S_cpu_eff = cpuSpeed * (1 - idleFactor)

T_cpu = numInstructions / S_cpu_eff
T_io  = readOps / ioRead  + writeOps / ioWrite
T_net = txMB / txSpeed    + rxMB / rxSpeed

T = ( T_cpu^p + T_io^p + T_net^p )^(1/p)
```

- `p = 1` adds the components; `p -> infinity` approaches the maximum; values in `[2, 4]` are a smooth trade-off.
- The result is in seconds (speeds are per second).
- If several resources are assigned to one task, each component uses the **slowest** assigned resource that defines
  the needed speed (for the CPU, the lowest effective speed after the idle factor).
- The sum is evaluated relative to the largest component, so a large `p` cannot overflow.
- RAM is not part of the model. A `ram` task attribute is rejected with an error listing the accepted keys.

### 1.3 Plugin `affinity` (scheduling)

| Class | Role |
|---|---|
| `AffinitySCParserPlugin` | Parses and validates `<affinity>` rules of tasks. |
| `AffinityHistoryPlugin` | Task-begin plugin that records which resources executed each task of a process instance. |
| `AffinityRankingPlugin` | Implements `ResourceTupleRankingPluggable`: removes candidates that violate a hard rule and orders the rest by the summed weight of satisfied soft rules. Equal scores keep the default order. |
| `AffinityRule`, `AffinityHistory`, `AffinityPluginUtils` | Rule model, per-process-instance history (weakly keyed, so it disappears with the process instance), helpers. |

Rule semantics:

- A rule targets either a **resource** (type, optionally one instance) or the **history** (the resources that ran
  an earlier task of the same process instance, or the previously started task if no task is given).
- `relation` is `affinity` (prefer / require the target) or `antiAffinity` (avoid / forbid it).
- `mode="hard"` removes violating candidates. `mode="soft"` adds `weight` to the score of satisfying candidates.
- A history rule does not apply until its reference task has started in that process instance.
- Parse-time validation: unknown resources, instances or tasks; bad `mode`, `relation` or `weight`; and a positive
  resource rule on a type the task does not use (it could never be satisfied and would block the task forever).

## 2. Configuration

### 2.1 Global configuration

```xml
<bsim:globalConfiguration ... softmaxExponent="3">
  <bsim:resourceData>
    <bsim:dynamicResource id="server" defaultQuantity="4" defaultCost="4000.0" defaultTimeUnit="SECONDS"
        defaultTimetableId="t1"
        cpuSpeed="4000" idleFactor="0.1" ioRead="5" ioWrite="5" txSpeed="5" rxSpeed="5">
      <bsim:instance name="slowServer" cpuSpeed="1000" />   <!-- overrides the type defaults -->
    </bsim:dynamicResource>
  </bsim:resourceData>
</bsim:globalConfiguration>
```

| Attribute | Where | Meaning | Constraint |
|---|---|---|---|
| `softmaxExponent` | root | exponent `p` | number >= 1, default 1 |
| `cpuSpeed` | resource, instance | instructions per second | > 0 (default: `defaultCost`) |
| `idleFactor` | resource, instance | CPU idle factor `alpha`, default 0 | in `[0, 1)` |
| `ioRead`, `ioWrite` | resource, instance | operations per second | > 0 |
| `txSpeed`, `rxSpeed` | resource, instance | MB per second | > 0 |

### 2.2 Simulation configuration

```xml
<bsim:Task id="Server_Send_HTML">
  <bsim:affinity>
    <bsim:resource id="server" instance="#2" relation="affinity" mode="soft" weight="3" />
  </bsim:affinity>
  <bsim:resources><bsim:resource id="server" amount="1" /></bsim:resources>
  <bsim:attribute key="numInstructions" value="3000" />
  <bsim:attribute key="readOps" value="30" />
  <bsim:attribute key="writeOps" value="10" />
  <bsim:attribute key="txMB" value="2" />
  <bsim:attribute key="rxMB" value="1" />
</bsim:Task>

<bsim:Task id="Server_Send_IMG">
  <bsim:affinity>
    <!-- same server that sent the HTML -->
    <bsim:history task="Server_Send_HTML" relation="affinity" mode="hard" />
  </bsim:affinity>
  ...
</bsim:Task>
```

Workload keys: `numInstructions`, `readOps`, `writeOps`, `txMB`, `rxMB` (numbers >= 0).
Affinity elements: `<resource id=… [instance=…]>` and `<history [task=…]>`, each with `relation`
(`affinity` | `antiAffinity`, default `affinity`), `mode` (`soft` | `hard`, default `soft`) and `weight`
(positive, default 1, ignored for hard rules).

Automatically created instances are named `#0`, `#1`, … by the core.

## 3. Example

Files in `samples/client_server_masiello/`:

- `clientserver.bpmn`, `clientserver.global.xml`: client sends requests, server answers.
- `clientserver.config.xml`: workload only (no affinity).
- `clientserver_affinity.config.xml`: two process instances, soft preference for server `#2` on the HTML task and a
  hard "same server as the HTML task" rule on the image task.

Run (from the project root, after `mvn clean package`):

```bash
S=samples/client_server_masiello
java -jar target/scylla-0.0.1-SNAPSHOT.jar --headless \
  --config=$S/clientserver.global.xml --bpmn=$S/clientserver.bpmn \
  --sim=$S/clientserver_affinity.config.xml --enable-bps-logging
```

The output folder (`output_<timestamp>`, containing the XES log) is created next to the global configuration file.
The `--output` argument is accepted by the command line but has no effect in headless mode: the value is stored in
`SimulationManager` and never used. Copy the configuration to a scratch folder if you do not want output inside
`samples/`.

## 4. Logs

The plugins write to the console through `DebugLogger` with the prefixes `[resourceCost]` and `[affinity]`
(switch them off with `-Dscylla.quiet=true` on the `java` command line, or `DebugLogger.allowDebugLogging = false`
in code). Every line carries the simulated time, the process
instance and the task. Real output of the example run:

**Parsing of resources, exponent, workloads and rules**

```
[DEBUG] [resourceCost] resource type 'server': {cpuSpeed=4000.0, ioRead=5.0, ioWrite=5.0, rxSpeed=5.0, txSpeed=5.0}
[DEBUG] [resourceCost] softmax exponent p = 3.0
[DEBUG] [resourceCost] workload of task 'Server_Send_HTML': {numInstructions=3000.0, readOps=30.0, rxMB=1.0, txMB=2.0, writeOps=10.0}
[DEBUG] [affinity] parsed rule of task 'Server_Send_HTML': SOFT affinity to resource server instance #2 (weight 3.0)
[DEBUG] [affinity] parsed rule of task 'Server_Send_IMG': HARD affinity to resources of task 'Server_Send_HTML'
```

**Scheduling and affinity ranking** (one line per candidate: rejected by which rule, or admissible with its score;
the last line is the order in which the scheduler will try the candidates)

```
[affinity] 2025-01-01T00:00:45+01:00 clientserver_PI1#1 task 'Server - Send HTML': ranking 4 candidate(s) with 1 rule(s)
[affinity]   [server_#3] admissible, score 0.0
[affinity]   [server_#2] admissible, score 3.0 +3.0
[affinity]   [server_#1] admissible, score 0.0
[affinity]   [server_#0] admissible, score 0.0
[affinity]   result: [server_#2] > [server_#3] > [server_#1] > [server_#0]
[affinity] 2025-01-01T00:00:45+01:00 clientserver_PI2#1 task 'Server - Send HTML': ranking 3 candidate(s) with 1 rule(s)
[affinity]   result: [server_#3] > [server_#0] > [server_#1]
...
[affinity] 2025-01-01T00:00:53+01:00 clientserver_PI1#1 task 'Server - Send IMG': ranking 4 candidate(s) with 1 rule(s)
[affinity]   [server_#0] rejected, violates HARD affinity to resources of task 'Server_Send_HTML'
[affinity]   [server_#1] rejected, violates HARD affinity to resources of task 'Server_Send_HTML'
[affinity]   [server_#2] admissible, score 0.0
[affinity]   [server_#3] rejected, violates HARD affinity to resources of task 'Server_Send_HTML'
[affinity]   result: [server_#2]
```

Process instance 2 sees only three candidates because instance 1 already holds `server_#2`; its image task still
follows its own history to `server_#3`. A rule whose reference task has not started yet is reported as
`rule not applicable yet (referenced task has not started): …`; if every candidate is rejected the result line
reads `no admissible resource, task has to wait`. The ranking is evaluated each time the scheduler checks a task
(including availability checks of queued tasks), so lines can repeat for waiting tasks.

**History recording and dynamic execution time**

```
[affinity] 2025-01-01T00:00:45+01:00 clientserver_PI1#1 task 'Server - Send HTML': recorded in history, executed by [server_#2]
[resourceCost] 2025-01-01T00:00:53+01:00 clientserver_PI1#1 task 'Server - Send IMG': workload {...} on [server_#2]
[resourceCost]   T_cpu=2.000s T_io=16.000s T_net=2.200s, p=3.0 -> service time T=16.024s
[resourceCost]   task terminates after 16 SECONDS (at 2025-01-01T00:01:09+01:00[Europe/Rome])
```

The last line shows the effective duration after timetables are applied, in whole seconds of the simulator's time unit
(fractions are truncated, see the cache experiments).
(seconds).

## 5. Tests performed

### 5.1 Unit tests (JUnit 5)

`mvn -o test -Dtest=AffinityRankingTests,ResourceCostModelTests` (17 tests, all passing):

- `AffinityRankingTests` (9): soft affinity prefers the matching instance; equal scores keep the default order;
  hard anti-affinity removes a candidate; an unsatisfiable hard rule yields no candidates; history affinity follows
  the referenced task; a history rule without history does not apply; previous-task anti-affinity avoids the last
  resource; higher weight wins; the log names the rejecting rule, the scores and rules that do not apply yet.
- `ResourceCostModelTests` (8), with hand-computed values for `T_cpu = 10 s`, `T_io = 4 s`, `T_net = 3 s`:
  `p = 1` gives 17 s; `p = 2` gives `sqrt(125)`; `p = 1000` gives a value between 10 s and 10.01 s without
  overflow; an idle factor of 0.5 doubles `T_cpu` (`p = 2` gives `sqrt(425)`); instance values override type
  defaults; the slowest assigned resource is the bottleneck; a task without workload takes 0 s; a missing speed is
  reported.

### 5.2 Regression

The existing `TimetableTests`, `RegressionSystemTests` and `EventArrivalRatePluginTests`, together with the new
tests above, were run against the final code: 22 tests, all passing. This covers the `QueueManager` change.

### 5.3 End-to-end runs of the client-server sample

- **Duration formula** (single instance, `p = 3`). The XES log matches the formula:

  | Task | T_cpu | T_io | T_net | T (p=3) | In log |
  |---|---|---|---|---|---|
  | Client_HTTP_Request | 1.0 | 15.0 | 2.0 | 15.01 | 15 s |
  | Client_IMG_Request | 2.0 | 30.0 | 7.0 | 30.13 | 30 s |
  | Server_Send_HTML | 0.75 | 8.0 | 0.6 | 8.00 | 8 s |
  | Server_Send_IMG | 2.0 | 16.0 | 2.2 | 16.02 | 16 s |
  | Client_Process_Response | 0.5 | 10.0 | 1.0 | 10.00 | 10 s |

  Total simulated time 79 s. The earlier additive model (`p = 1`, with a RAM term) gave 203 s for the same sample.
- **Affinity, single instance.** With the soft preference for `#2` and the hard history rule, both server tasks ran
  on `server_#2`. The same sample without affinity rules split the server tasks between `server_#2` and `server_#3`.
- **Affinity, two instances.** Both process instances executed their HTML and image tasks on the same server
  (`server_#2` and `server_#3` respectively), with the ranking decisions shown in section 4.

## 6. Limitations

For two synthetic scenarios that use the extension (a proxy cache validated against queueing theory and a distributed
cache with affinity) and their measured results, see [cache-experiments.md](cache-experiments.md).

- History is kept per process instance and per scope: a sub-process has its own history, and a rule cannot refer to
  a task of another scope. A history rule does not apply before its reference task has started (for example, a hard
  history rule constrains nothing for the first task).
- A hard rule that can never be satisfied blocks the task. Parse-time validation only catches this for positive
  resource rules.
- The service time uses fixed per-second speeds. There is no model of contention between concurrently running tasks
  on the same resource and no link model (latency, bandwidth) between a sender and a receiver.
- The idle factor is a static attribute of a resource, not a function of the simulated load.
- Only `mvn` tests and runs started on the compiled classes were executed. The `java -jar` command in section 3
  follows the README but was not run as such.
