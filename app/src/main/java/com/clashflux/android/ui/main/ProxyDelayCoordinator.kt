package com.clashflux.android.ui.main

import com.clashflux.android.core.YamlProxyGroupParser
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.model.ProxyGroup
import com.clashflux.android.model.ProxyNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Coordinates proxy group warmup, per-group/per-node delay probes, and epoch-based cancellation
 * so stale in-flight tests from an earlier profile never overwrite newly loaded groups.
 */
internal class ProxyDelayCoordinator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val env: MainRuntimeEnvironment,
    private val proxyGroups: MutableStateFlow<List<ProxyGroup>>,
    private val nodeHostPortMap: ConcurrentHashMap<String, Pair<String, Int>>
) {
    private val groupDelayJobs = ConcurrentHashMap<String, Job>()
    private val nodeDelayJobs = ConcurrentHashMap<String, Job>()
    private val profileEpoch = AtomicLong(0L)
    private val warmUpMutex = Mutex()
    private var lastWarmedEpoch = -1L
    private var lastWarmedConnected = false

    fun currentEpoch(): Long = profileEpoch.get()

    fun resetForConfigChange(): Long {
        val nextEpoch = profileEpoch.incrementAndGet()
        groupDelayJobs.values.forEach { it.cancel() }
        groupDelayJobs.clear()
        nodeDelayJobs.values.forEach { it.cancel() }
        nodeDelayJobs.clear()
        return nextEpoch
    }

    suspend fun warmUpPrimaryGroupDelays() {
        warmUpMutex.withLock {
            val epoch = profileEpoch.get()
            val isConnected = env.serviceState.value == ConnectionStatus.Connected
            if (lastWarmedEpoch == epoch && (lastWarmedConnected || !isConnected)) {
                return
            }

            val primaryGroup = YamlProxyGroupParser.selectPrimaryGroup(proxyGroups.value)?.name
                ?: return

            val delayMap = mutableMapOf<String, Int>()
            env.testControllerGroupDelay(primaryGroup)?.let { delayMap.putAll(it) }
            if (epoch != profileEpoch.get()) return

            if (delayMap.isEmpty()) {
                delayMap.putAll(env.nativeHealthCheckGroup(primaryGroup))
                if (epoch != profileEpoch.get()) return
            }
            if (!delayMap.containsKey("DIRECT")) {
                val directDelay = env.measureDirectHttpDelay()
                if (directDelay > 0) delayMap["DIRECT"] = directDelay
            }
            if (epoch != profileEpoch.get()) return

            val groupsSnapshot = proxyGroups.value
            groupsSnapshot.forEach { group ->
                if (!delayMap.containsKey(group.name) || (delayMap[group.name] ?: -1) <= 0) {
                    val subDelay = delayMap[group.now]
                        ?: group.all.mapNotNull { delayMap[it.name] }.filter { it > 0 }.minOrNull()
                    if (subDelay != null && subDelay > 0) {
                        delayMap[group.name] = subDelay
                    }
                }
            }
            if (delayMap.isNotEmpty() && epoch == profileEpoch.get()) {
                proxyGroups.update { groups ->
                    groups.map { group ->
                        group.copy(
                            all = group.all.map { node ->
                                val d = delayMap[node.name]
                                if (d != null && d > 0) node.copy(delay = d, isTesting = false) else node
                            }
                        )
                    }
                }
            }
            if (epoch == profileEpoch.get()) {
                lastWarmedEpoch = epoch
                lastWarmedConnected = isConnected
            }
        }
    }

    fun testGroupDelay(groupName: String, syncLiveGroups: suspend () -> Unit) {
        if (groupDelayJobs[groupName]?.isActive == true) return
        val epoch = profileEpoch.get()
        val job = scope.launch(ioDispatcher) {
            proxyGroups.update { groups ->
                groups.map { group ->
                    if (group.name == groupName) {
                        group.copy(all = group.all.map { it.copy(isTesting = true) })
                    } else {
                        group
                    }
                }
            }
            env.showToast("正在测试「$groupName」延迟...")

            val delayMap = mutableMapOf<String, Int>()
            env.testControllerGroupDelay(groupName)?.let { delayMap.putAll(it) }
            if (epoch != profileEpoch.get()) return@launch
            if (delayMap.isEmpty()) {
                delayMap.putAll(env.nativeHealthCheckGroup(groupName))
                if (epoch != profileEpoch.get()) return@launch
            }

            val groupsSnapshot = proxyGroups.value
            val nodesToTest = groupsSnapshot.find { it.name == groupName }?.all.orEmpty()
            val remaining = nodesToTest.filter { node ->
                val existing = delayMap[node.name]
                existing == null || existing <= 0
            }

            if (remaining.isNotEmpty()) {
                val semaphore = Semaphore(6)
                coroutineScope {
                    remaining.map { node ->
                        async {
                            semaphore.withPermit {
                                node.name to measureSingleNodeDelay(node.name, groupsSnapshot)
                            }
                        }
                    }.awaitAll().forEach { (name, d) ->
                        delayMap[name] = d
                    }
                }
            }

            if (epoch != profileEpoch.get()) return@launch
            resolveSubGroupDelays(nodesToTest, groupsSnapshot, delayMap)
            applyGroupDelayResults(groupName, delayMap)
            if (epoch == profileEpoch.get()) {
                syncLiveGroups()
                env.showToast("测速完成")
            }
        }
        groupDelayJobs[groupName] = job
        job.invokeOnCompletion { groupDelayJobs.remove(groupName, job) }
    }

    fun testNodeDelay(groupName: String, nodeName: String) {
        val key = "$groupName\u0000$nodeName"
        if (groupDelayJobs[groupName]?.isActive == true || nodeDelayJobs[key]?.isActive == true) return

        val epoch = profileEpoch.get()
        val job = scope.launch(ioDispatcher) {
            proxyGroups.update { groups ->
                groups.map { group ->
                    if (group.name == groupName) {
                        group.copy(
                            all = group.all.map { node ->
                                if (node.name == nodeName) node.copy(isTesting = true) else node
                            }
                        )
                    } else {
                        group
                    }
                }
            }

            val delay = measureSingleNodeDelay(nodeName, proxyGroups.value)
            if (epoch != profileEpoch.get()) return@launch

            proxyGroups.update { groups ->
                groups.map { group ->
                    if (group.name == groupName) {
                        group.copy(
                            all = group.all.map { node ->
                                if (node.name == nodeName) {
                                    node.copy(delay = if (delay > 0) delay else -1, isTesting = false)
                                } else {
                                    node
                                }
                            }
                        )
                    } else {
                        group
                    }
                }
            }
        }
        nodeDelayJobs[key] = job
        job.invokeOnCompletion { nodeDelayJobs.remove(key, job) }
    }

    private suspend fun measureSingleNodeDelay(nodeName: String, groupsSnapshot: List<ProxyGroup>): Int {
        if (nodeName.equals("REJECT", ignoreCase = true)) return -1

        env.testControllerProxyDelay(nodeName)?.let { d ->
            if (d in 1..65534) return d
        }

        if (nodeName.equals("DIRECT", ignoreCase = true)) {
            return env.measureDirectHttpDelay()
        }

        val subGroup = groupsSnapshot.find { it.name == nodeName }
        val targetNode = subGroup?.now ?: nodeName
        val hostPort = nodeHostPortMap[targetNode] ?: return -1
        return env.measureTcpConnectDelay(hostPort.first, hostPort.second)
    }

    private fun resolveSubGroupDelays(
        nodesToTest: List<ProxyNode>,
        groupsSnapshot: List<ProxyGroup>,
        delayMap: MutableMap<String, Int>
    ) {
        for (node in nodesToTest) {
            val subGroup = groupsSnapshot.find { it.name == node.name } ?: continue
            val subDelay = delayMap[subGroup.now]
                ?: subGroup.all.mapNotNull { delayMap[it.name] }.filter { it > 0 }.minOrNull()
            val currentDelay = delayMap[node.name] ?: -1
            if (subDelay != null && subDelay > 0 && (currentDelay <= 0 || subDelay < currentDelay)) {
                delayMap[node.name] = subDelay
            }
        }
    }

    private fun applyGroupDelayResults(targetGroupName: String, delayMap: Map<String, Int>) {
        proxyGroups.update { groups ->
            groups.map { group ->
                if (group.name == targetGroupName) {
                    group.copy(
                        all = group.all.map { node ->
                            val realDelay = delayMap[node.name]
                            node.copy(
                                delay = if (realDelay != null && realDelay > 0) realDelay else -1,
                                isTesting = false
                            )
                        }
                    )
                } else {
                    group.copy(
                        all = group.all.map { node ->
                            val realDelay = delayMap[node.name]
                            if (realDelay != null && realDelay > 0) {
                                node.copy(delay = realDelay, isTesting = false)
                            } else {
                                node
                            }
                        }
                    )
                }
            }
        }
    }
}
