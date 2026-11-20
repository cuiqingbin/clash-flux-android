package com.clashflux.android.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clashflux.android.model.ClashRule
import com.clashflux.android.theme.VergePrimary

private val RULE_TYPES = listOf(
    "DOMAIN-SUFFIX" to "匹配域名后缀 (DOMAIN-SUFFIX)",
    "DOMAIN" to "匹配完整域名 (DOMAIN)",
    "DOMAIN-KEYWORD" to "匹配关键字 (DOMAIN-KEYWORD)",
    "IP-CIDR" to "匹配 IP 网段 (IP-CIDR)",
    "IP-CIDR6" to "匹配 IPv6 网段 (IP-CIDR6)",
    "GEOIP" to "国家代码 (GEOIP)",
    "GEOSITE" to "地理位置集合 (GEOSITE)",
    "RULE-SET" to "外部规则集 (RULE-SET)",
    "PROCESS-NAME" to "进程名称 (PROCESS-NAME)",
    "MATCH" to "匹配所有 (MATCH)"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRulesDialog(
    profileName: String,
    initialRules: List<ClashRule>,
    availablePolicies: List<String>,
    initialSelectedPolicy: String? = null,
    isSaving: Boolean = false,
    saveError: String? = null,
    onDismiss: () -> Unit,
    onSave: (List<ClashRule>) -> Unit
) {
    var rules by remember { mutableStateOf(initialRules) }
    var selectedType by remember { mutableStateOf("DOMAIN-SUFFIX") }
    var rulePayload by remember { mutableStateOf("") }
    var selectedPolicy by remember {
        mutableStateOf(
            initialSelectedPolicy?.takeIf { availablePolicies.contains(it) }
                ?: availablePolicies.firstOrNull()
                ?: "DIRECT"
        )
    }
    var noResolve by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }
    var isAdvancedMode by remember { mutableStateOf(false) }
    var rawTextRules by remember { mutableStateOf("") }

    val density = LocalDensity.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val isWideScreen = with(density) { containerWidthPx.toDp() >= 640.dp }

    val filteredRules = remember(rules, filterText) {
        if (filterText.isBlank()) rules
        else {
            val query = filterText.trim().lowercase()
            rules.filter {
                it.payload.lowercase().contains(query) ||
                it.type.lowercase().contains(query) ||
                it.policy.lowercase().contains(query)
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = !isSaving
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isWideScreen) 0.88f else 0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "编辑规则",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "配置: $profileName (共 ${rules.size} 条规则)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    // Top-right "高级" Button
                    Button(
                        onClick = {
                            if (!isAdvancedMode) {
                                rawTextRules = rules.joinToString("\n") { it.toRuleLine().trim().removePrefix("-").trim() }
                                isAdvancedMode = true
                            } else {
                                // Parse raw text back into rules list (including intentionally cleared list)
                                rules = rawTextRules.lines().mapNotNull { ClashRule.fromLine(it) }
                                isAdvancedMode = false
                            }
                        },
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAdvancedMode) VergePrimary else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isAdvancedMode) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isAdvancedMode) "普通模式" else "高级",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Content Area
                Box(modifier = Modifier.weight(1f)) {
                    if (isAdvancedMode) {
                        // Advanced Raw Rules Text Editor
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "高级规则编辑（每行一条规则，格式: 类型,内容,策略[,no-resolve]）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = rawTextRules,
                                onValueChange = { input ->
                                    rawTextRules = input.filter { ch -> ch >= ' ' || ch == '\n' || ch == '\r' || ch == '\t' }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                textStyle = LocalTextStyle.current.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                ),
                                placeholder = { Text("DOMAIN-SUFFIX,google.com,DIRECT\nMATCH,🚀 节点选择") }
                            )
                        }
                    } else {
                        // Visual Mode: responsive layout
                        val addRule: (prepend: Boolean) -> Unit = { prepend ->
                            if (selectedType == "MATCH" || rulePayload.isNotBlank()) {
                                val newRule = ClashRule(
                                    type = selectedType,
                                    payload = if (selectedType == "MATCH") "" else rulePayload.trim(),
                                    policy = selectedPolicy,
                                    noResolve = noResolve,
                                    isCustom = true
                                )
                                rules = if (prepend) {
                                    listOf(newRule) + rules
                                } else {
                                    val matchIdx = rules.indexOfLast { it.type.equals("MATCH", ignoreCase = true) }
                                    if (matchIdx != -1) {
                                        rules.subList(0, matchIdx) + newRule + rules.subList(matchIdx, rules.size)
                                    } else {
                                        rules + newRule
                                    }
                                }
                                rulePayload = ""
                            }
                        }

                        val formSection: @Composable () -> Unit = {
                            RuleFormSection(
                                selectedType = selectedType,
                                onTypeSelected = { selectedType = it },
                                rulePayload = rulePayload,
                                onPayloadChanged = { rulePayload = it },
                                selectedPolicy = selectedPolicy,
                                onPolicySelected = { selectedPolicy = it },
                                availablePolicies = availablePolicies,
                                noResolve = noResolve,
                                onNoResolveChanged = { noResolve = it },
                                onPrependRule = { addRule(true) },
                                onAppendRule = { addRule(false) }
                            )
                        }

                        val listSection: @Composable () -> Unit = {
                            RuleListSection(
                                filterText = filterText,
                                onFilterChanged = { filterText = it },
                                filteredRules = filteredRules,
                                onDeleteRule = { ruleToDelete ->
                                    rules = rules.filterNot { it.id == ruleToDelete.id }
                                }
                            )
                        }

                        if (isWideScreen) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                Box(modifier = Modifier.weight(0.42f)) { formSection() }
                                Box(modifier = Modifier.weight(0.58f)) { listSection() }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                formSection()
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                Box(modifier = Modifier.weight(1f)) { listSection() }
                            }
                        }
                    }
                }

                if (!saveError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = saveError,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Footer Buttons: 取消 / 保存
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text("取消")
                    }

                    Button(
                        onClick = {
                            val finalRules = if (isAdvancedMode) {
                                rawTextRules.lines().mapNotNull { ClashRule.fromLine(it) }
                            } else {
                                rules
                            }
                            onSave(finalRules)
                        },
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = VergePrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("保存中...", color = Color.White)
                        } else {
                            Text("保存", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleFormSection(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    rulePayload: String,
    onPayloadChanged: (String) -> Unit,
    selectedPolicy: String,
    onPolicySelected: (String) -> Unit,
    availablePolicies: List<String>,
    noResolve: Boolean,
    onNoResolveChanged: (Boolean) -> Unit,
    onPrependRule: () -> Unit,
    onAppendRule: () -> Unit
) {
    var typeDropdownOpen by remember { mutableStateOf(false) }
    var policyDropdownOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 规则类型
        Column {
            Text(
                text = "规则类型",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            ExposedDropdownMenuBox(
                expanded = typeDropdownOpen,
                onExpandedChange = { typeDropdownOpen = !typeDropdownOpen }
            ) {
                OutlinedTextField(
                    value = RULE_TYPES.find { it.first == selectedType }?.second ?: selectedType,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownOpen) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                )
                ExposedDropdownMenu(
                    expanded = typeDropdownOpen,
                    onDismissRequest = { typeDropdownOpen = false }
                ) {
                    RULE_TYPES.forEach { (typeKey, typeLabel) ->
                        DropdownMenuItem(
                            text = { Text(typeLabel, fontSize = 13.sp) },
                            onClick = {
                                onTypeSelected(typeKey)
                                typeDropdownOpen = false
                            }
                        )
                    }
                }
            }
        }

        // 规则内容
        if (selectedType != "MATCH") {
            Column {
                Text(
                    text = "规则内容",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = rulePayload,
                    onValueChange = onPayloadChanged,
                    placeholder = {
                        val hint = when (selectedType) {
                            "IP-CIDR", "IP-CIDR6" -> "192.168.0.0/16"
                            "GEOIP" -> "CN"
                            "GEOSITE" -> "geolocation-cn"
                            "PROCESS-NAME" -> "chrome.exe"
                            else -> "example.com"
                        }
                        Text(hint, fontSize = 13.sp)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    singleLine = true
                )
            }
        }

        // 代理策略
        Column {
            Text(
                text = "代理策略",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            ExposedDropdownMenuBox(
                expanded = policyDropdownOpen,
                onExpandedChange = { policyDropdownOpen = !policyDropdownOpen }
            ) {
                OutlinedTextField(
                    value = selectedPolicy,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = policyDropdownOpen) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                )
                ExposedDropdownMenu(
                    expanded = policyDropdownOpen,
                    onDismissRequest = { policyDropdownOpen = false }
                ) {
                    availablePolicies.forEach { policyName ->
                        DropdownMenuItem(
                            text = { Text(policyName, fontSize = 13.sp) },
                            onClick = {
                                onPolicySelected(policyName)
                                policyDropdownOpen = false
                            }
                        )
                    }
                }
            }
        }

        if (selectedType.startsWith("IP-CIDR") || selectedType == "GEOIP") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Checkbox(
                    checked = noResolve,
                    onCheckedChange = onNoResolveChanged,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("no-resolve（不触发 DNS 解析）", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Buttons: 添加前置规则 / 添加后置规则
        val canAdd = selectedType == "MATCH" || rulePayload.isNotBlank()

        Button(
            onClick = onPrependRule,
            enabled = canAdd,
            colors = ButtonDefaults.buttonColors(containerColor = VergePrimary),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("添加前置规则", fontSize = 13.sp)
        }

        Button(
            onClick = onAppendRule,
            enabled = canAdd,
            colors = ButtonDefaults.buttonColors(containerColor = VergePrimary),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("添加后置规则", fontSize = 13.sp)
        }
    }
}

@Composable
private fun RuleListSection(
    filterText: String,
    onFilterChanged: (String) -> Unit,
    filteredRules: List<ClashRule>,
    onDeleteRule: (ClashRule) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Filter search input
        OutlinedTextField(
            value = filterText,
            onValueChange = onFilterChanged,
            placeholder = { Text("过滤条件", fontSize = 13.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (filterText.isNotEmpty()) {
                    IconButton(onClick = { onFilterChanged("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            shape = RoundedCornerShape(8.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )

        // Rules List
        if (filteredRules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (filterText.isEmpty()) "暂无规则" else "无匹配规则",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(filteredRules, key = { _, r -> r.id }) { _, rule ->
                    RuleItemCard(rule = rule, onDelete = { onDeleteRule(rule) })
                }
            }
        }
    }
}

@Composable
private fun RuleItemCard(
    rule: ClashRule,
    onDelete: () -> Unit
) {
    // Custom/added rules use the distinct pleasant green background exactly like Image 2!
    val isDark = MaterialTheme.colorScheme.surface.let {
        // Simple luminance check
        (0.299 * it.red + 0.587 * it.green + 0.114 * it.blue) < 0.5
    }

    val customBg = if (isDark) Color(0xFF1E3A2F) else Color(0xFFD5F3DF)
    val customBorder = if (isDark) Color(0xFF2E6B50) else Color(0xFFA5D6A7)
    val defaultBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val defaultBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)

    val cardBg = if (rule.isCustom) customBg else defaultBg
    val cardBorder = if (rule.isCustom) customBorder else defaultBorder

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, cardBorder, RoundedCornerShape(8.dp)),
        color = cardBg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Rule Payload (e.g. challenges.cloudflare.com)
                Text(
                    text = if (rule.type == "MATCH") "所有未匹配流量 (MATCH)" else rule.payload,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Type Badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = rule.type,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Policy text
                    Text(
                        text = rule.policy,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    if (rule.noResolve) {
                        Text(
                            text = "[no-resolve]",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Trash / Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete Rule",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
