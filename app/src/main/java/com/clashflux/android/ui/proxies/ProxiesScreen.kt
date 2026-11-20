package com.clashflux.android.ui.proxies

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashflux.android.model.ClashRule
import com.clashflux.android.model.ProxyGroup
import com.clashflux.android.model.ProxyNode
import com.clashflux.android.theme.StatusConnected
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.ui.components.ContextMenuItem
import com.clashflux.android.ui.components.LatencyBadge
import com.clashflux.android.ui.components.VergeCard
import com.clashflux.android.ui.components.copyToClipboard
import com.clashflux.android.ui.profiles.EditRulesDialog
import com.clashflux.android.ui.profiles.ProfileAsyncEditorHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(
    groups: List<ProxyGroup>,
    onSelectNode: (groupName: String, nodeName: String) -> Unit,
    onTestGroupDelay: (groupName: String) -> Unit,
    modifier: Modifier = Modifier,
    onTestNodeDelay: (groupName: String, nodeName: String) -> Unit = { _, _ -> },
    currentProfileName: String = "",
    activeProfileId: String? = null,
    loadProfileRulesData: suspend (profileId: String) -> Pair<List<ClashRule>, List<String>> = {
        emptyList<ClashRule>() to emptyList()
    },
    onSaveProfileRules: suspend (profileId: String, rules: List<ClashRule>) -> Boolean = { _, _ -> false }
) {
    val context = LocalContext.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortByDelay by rememberSaveable { mutableStateOf(false) }

    // Local expansion state scoped to the active profile, pruned as groups change
    val profileKey = activeProfileId ?: currentProfileName
    val expandedMap = remember(profileKey) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(groups, profileKey) {
        val validNames = groups.map { it.name }.toSet()
        expandedMap.keys.toList().filterNot { it in validNames }.forEach { expandedMap.remove(it) }
        validNames.forEach { name ->
            if (!expandedMap.containsKey(name)) {
                expandedMap[name] = true
            }
        }
    }

    var selectedNodeMenu by remember { mutableStateOf<Pair<String, ProxyNode>?>(null) }
    var ruleCreationTargetNode by rememberSaveable { mutableStateOf<String?>(null) }

    val displayGroups = remember(groups, searchQuery, sortByDelay) {
        val query = searchQuery.trim()
        groups.mapNotNull { group ->
            val matchingNodes = if (query.isEmpty() || group.name.contains(query, ignoreCase = true)) {
                group.all
            } else {
                group.all.filter { node ->
                    node.name.contains(query, ignoreCase = true) ||
                        node.type.contains(query, ignoreCase = true)
                }
            }
            if (query.isNotEmpty() && !group.name.contains(query, ignoreCase = true) && matchingNodes.isEmpty()) {
                null
            } else {
                val sortedNodes = if (sortByDelay) {
                    matchingNodes.sortedWith(
                        compareBy<ProxyNode> { node ->
                            val d = node.delay
                            if (d != null && d > 0) d else Int.MAX_VALUE
                        }.thenBy { it.name }
                    )
                } else {
                    matchingNodes
                }
                group.copy(all = sortedNodes)
            }
        }
    }
    val allExpanded = groups.isNotEmpty() && groups.all { expandedMap[it.name] ?: true }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "代理节点",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (groups.isEmpty()) "暂无策略组" else "共 ${groups.size} 个策略组 (长按节点可快速创建规则)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }

                        if (groups.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { sortByDelay = !sortByDelay },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = "按延迟排序",
                                        tint = if (sortByDelay) VergePrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val next = !allExpanded
                                        groups.forEach { expandedMap[it.name] = next }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (allExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                        contentDescription = if (allExpanded) "全部折叠" else "全部展开",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                    )
                                }
                            }
                        }
                    }

                    if (groups.isNotEmpty()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索节点或策略组...", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            if (groups.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无节点，请在「订阅」页面添加并选中配置",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                }
            } else if (displayGroups.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "未匹配到符合「$searchQuery」的策略组或节点",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            items(displayGroups, key = { it.name }) { group ->
                val isExpanded = if (searchQuery.isNotBlank()) true else (expandedMap[group.name] ?: true)
                val rotationState by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    label = "chevronRotation"
                )

                VergeCard(modifier = Modifier.fillMaxWidth()) {
                    // Group Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedMap[group.name] = !isExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = group.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = group.type,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "当前: ${group.now}",
                                fontSize = 12.sp,
                                color = VergePrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isGroupTesting = group.all.any { it.isTesting }
                            // Test all nodes in group button
                            IconButton(
                                onClick = { onTestGroupDelay(group.name) },
                                enabled = !isGroupTesting,
                                modifier = Modifier.size(36.dp)
                            ) {
                                if (isGroupTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = VergePrimary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = "Test Delay",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = "Expand",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .size(24.dp)
                                    .rotate(rotationState)
                            )
                        }
                    }

                    // Collapsible Node List
                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            group.all.forEach { node ->
                                val isSelected = node.name == group.now

                                ProxyNodeItem(
                                    node = node,
                                    isSelected = isSelected,
                                    onClick = { onSelectNode(group.name, node.name) },
                                    onLongClick = { selectedNodeMenu = Pair(group.name, node) },
                                    onTestDelay = { onTestNodeDelay(group.name, node.name) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Long-press Context Menu for Proxy Node
        if (selectedNodeMenu != null) {
            val (groupName, node) = selectedNodeMenu!!
            ModalBottomSheet(
                onDismissRequest = { selectedNodeMenu = null },
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = node.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "策略组: $groupName  |  类型: ${node.type.uppercase()}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 1. 为此节点创建分流规则
                    ContextMenuItem(
                        icon = Icons.AutoMirrored.Filled.AltRoute,
                        title = "为此节点创建分流规则",
                        subtitle = "快速在「编辑规则」中为此节点添加域名或 IP 规则",
                        onClick = {
                            selectedNodeMenu = null
                            ruleCreationTargetNode = node.name
                        }
                    )

                    // 2. 选择此节点
                    ContextMenuItem(
                        icon = Icons.Default.CheckCircle,
                        title = "切换至此节点",
                        subtitle = "设置「$groupName」当前使用此节点",
                        tint = StatusConnected,
                        onClick = {
                            onSelectNode(groupName, node.name)
                            selectedNodeMenu = null
                        }
                    )

                    // 3. 测试此节点延迟
                    ContextMenuItem(
                        icon = Icons.Default.Speed,
                        title = "测试此节点延迟",
                        subtitle = "单独对该节点执行 HTTP 握手测速",
                        onClick = {
                            onTestNodeDelay(groupName, node.name)
                            selectedNodeMenu = null
                        }
                    )

                    // 4. 复制节点名称
                    ContextMenuItem(
                        icon = Icons.Default.ContentCopy,
                        title = "复制节点名称",
                        subtitle = "将名称「${node.name}」复制到剪贴板",
                        onClick = {
                            copyToClipboard(context, "proxy_node_name", node.name, "已复制节点名称")
                            selectedNodeMenu = null
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // Quick Rules Dialog from Proxies Tab
        val targetNode = ruleCreationTargetNode
        val profileId = activeProfileId
        if (targetNode != null && profileId != null) {
            ProfileAsyncEditorHost(
                loadKey = "quick-rule:$profileId:$targetNode",
                loadData = { loadProfileRulesData(profileId) },
                onDismiss = { ruleCreationTargetNode = null },
                onSave = { updatedRules -> onSaveProfileRules(profileId, updatedRules) },
                successMessage = "分流规则已创建并生效",
                failureMessage = "分流规则保存失败",
                loadingMessage = "正在解析分流规则..."
            ) { (initialRules, policies), isSaving, saveError, handleDismiss, handleSave ->
                EditRulesDialog(
                    profileName = currentProfileName.ifBlank { "当前配置" },
                    initialRules = initialRules,
                    availablePolicies = policies,
                    initialSelectedPolicy = targetNode,
                    isSaving = isSaving,
                    saveError = saveError,
                    onDismiss = handleDismiss,
                    onSave = handleSave
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProxyNodeItem(
    node: ProxyNode,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTestDelay: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        VergePrimary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = VergePrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Column {
                    Text(
                        text = node.name,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) VergePrimary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = node.type.uppercase(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            LatencyBadge(
                delay = node.delay,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onTestDelay() },
                isTesting = node.isTesting
            )
        }
    }
}
