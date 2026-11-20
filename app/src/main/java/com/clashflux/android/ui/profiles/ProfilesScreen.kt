package com.clashflux.android.ui.profiles

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashflux.android.model.ClashRule
import com.clashflux.android.model.Profile
import com.clashflux.android.theme.StatusConnected
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.ui.components.ContextMenuItem
import com.clashflux.android.ui.components.copyToClipboard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    onSelectProfile: (String) -> Unit,
    onUpdateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onAddProfile: suspend (name: String, url: String) -> Result<Unit>,
    modifier: Modifier = Modifier,
    onImportLocalProfile: suspend (name: String, yamlText: String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onUpdateAllProfiles: () -> Unit = {},
    busyProfileIds: Set<String> = emptySet(),
    isAddingProfile: Boolean = false,
    onEditProfileInfo: (profileId: String, name: String, url: String) -> Unit = { _, _, _ -> },
    loadProfileYaml: suspend (profileId: String) -> String = { "" },
    onSaveProfileYaml: suspend (profileId: String, content: String) -> Boolean = { _, _ -> false },
    hasProfileBackup: suspend (profileId: String) -> Boolean = { false },
    onRestoreProfileBackup: suspend (profileId: String) -> Boolean = { false },
    loadProfileRulesData: suspend (profileId: String) -> Pair<List<ClashRule>, List<String>> = {
        emptyList<ClashRule>() to emptyList()
    },
    onSaveProfileRules: suspend (profileId: String, rules: List<ClashRule>) -> Boolean = { _, _ -> false },
    loadProfileProxies: suspend (profileId: String) -> List<Map<String, Any>> = { emptyList() },
    onSaveProfileProxies: suspend (profileId: String, proxies: List<Map<String, Any>>) -> Boolean = { _, _ -> false }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    var menuProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var editInfoProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var editFileProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var editRulesProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var editProxiesProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var profileToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var profileToRestoreId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    val menuProfile = remember(profiles, menuProfileId) { profiles.findById(menuProfileId) }
    val editInfoProfile = remember(profiles, editInfoProfileId) { profiles.findById(editInfoProfileId) }
    val editFileProfile = remember(profiles, editFileProfileId) { profiles.findById(editFileProfileId) }
    val editRulesProfile = remember(profiles, editRulesProfileId) { profiles.findById(editRulesProfileId) }
    val editProxiesProfile = remember(profiles, editProxiesProfileId) { profiles.findById(editProxiesProfileId) }
    val profileToDelete = remember(profiles, profileToDeleteId) { profiles.findById(profileToDeleteId) }
    val profileToRestore = remember(profiles, profileToRestoreId) { profiles.findById(profileToRestoreId) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-yaml")
    ) { targetUri ->
        val exportId = pendingExportProfileId
        pendingExportProfileId = null
        if (targetUri == null || exportId == null) return@rememberLauncherForActivityResult
        val exportingName = profiles.find { it.id == exportId }?.name ?: "配置"
        scope.launch {
            val yaml = loadProfileYaml(exportId)
            if (yaml.isBlank()) {
                Toast.makeText(context, "配置内容为空，无法导出", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val writeResult = writeExportedYaml(context, targetUri, yaml)
            Toast.makeText(
                context,
                writeResult.fold(
                    onSuccess = { "已导出「$exportingName」" },
                    onFailure = { it.message ?: "导出失败" }
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val hasRemoteProfiles = remember(profiles) {
        profiles.any {
            val u = it.url.trim()
            u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "配置订阅",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "长按配置卡片或点击「⋮」唤出编辑菜单",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                    if (hasRemoteProfiles) {
                        OutlinedButton(
                            onClick = onUpdateAllProfiles,
                            enabled = busyProfileIds.isEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("更新全部", fontSize = 12.sp)
                        }
                    }
                }
            }

            if (profiles.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无配置，请点击右下角按钮添加订阅",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCardItem(
                        profile = profile,
                        isUpdating = profile.id in busyProfileIds,
                        onSelect = { onSelectProfile(profile.id) },
                        onUpdate = { onUpdateProfile(profile.id) },
                        onDelete = { profileToDeleteId = profile.id },
                        onShowMenu = { menuProfileId = profile.id }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { if (!isAddingProfile) showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = VergePrimary,
            contentColor = Color.White
        ) {
            if (isAddingProfile) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Profile")
            }
        }

        if (showAddDialog) {
            AddProfileDialog(
                isAdding = isAddingProfile,
                onDismiss = {
                    if (!isAddingProfile) {
                        showAddDialog = false
                    }
                },
                onConfirm = { name, url ->
                    val res = onAddProfile(name, url)
                    if (res.isSuccess) {
                        showAddDialog = false
                    }
                    res
                },
                onImportLocalFile = { name, yamlText ->
                    val res = onImportLocalProfile(name, yamlText)
                    if (res.isSuccess) {
                        showAddDialog = false
                    }
                    res
                }
            )
        }

        profileToRestore?.let { restoreTarget ->
            AlertDialog(
                onDismissRequest = { profileToRestoreId = null },
                title = { Text("恢复上次备份", fontWeight = FontWeight.Bold) },
                text = { Text("确定要将「${restoreTarget.name}」回滚到上一次修改/更新前的 YAML 备份吗？") },
                confirmButton = {
                    Button(
                        onClick = {
                            profileToRestoreId = null
                            scope.launch {
                                onRestoreProfileBackup(restoreTarget.id)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VergePrimary)
                    ) {
                        Text("确认恢复", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { profileToRestoreId = null }) {
                        Text("取消")
                    }
                }
            )
        }

        if (profileToDelete != null) {
            AlertDialog(
                onDismissRequest = { profileToDeleteId = null },
                title = { Text("删除配置", fontWeight = FontWeight.Bold) },
                text = { Text("确定要删除配置「${profileToDelete.name}」吗？此操作无法撤销。") },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeleteProfile(profileToDelete.id)
                            profileToDeleteId = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("删除", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { profileToDeleteId = null }) {
                        Text("取消")
                    }
                }
            )
        }

        if (menuProfile != null) {
            val targetProfile = menuProfile
            ModalBottomSheet(
                onDismissRequest = { menuProfileId = null },
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
                        text = targetProfile.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = if (targetProfile.isSelected) "当前正在使用" else "配置管理",
                        fontSize = 12.sp,
                        color = if (targetProfile.isSelected) StatusConnected else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (!targetProfile.isSelected) {
                        ContextMenuItem(
                            icon = Icons.Default.CheckCircle,
                            title = "使用",
                            subtitle = "切换至此订阅配置并应用",
                            tint = StatusConnected,
                            onClick = {
                                onSelectProfile(targetProfile.id)
                                menuProfileId = null
                            }
                        )
                    }

                    ContextMenuItem(
                        icon = Icons.Default.Edit,
                        title = "编辑信息",
                        subtitle = "修改订阅名称和链接地址",
                        onClick = {
                            editInfoProfileId = targetProfile.id
                            menuProfileId = null
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.Description,
                        title = "编辑文件",
                        subtitle = "直接编辑 YAML 原始配置文件",
                        onClick = {
                            editFileProfileId = targetProfile.id
                            menuProfileId = null
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.AutoMirrored.Filled.AltRoute,
                        title = "编辑规则",
                        subtitle = "图形化添加前置/后置规则、过滤分流策略",
                        onClick = {
                            editRulesProfileId = targetProfile.id
                            menuProfileId = null
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.Dns,
                        title = "编辑节点",
                        subtitle = "查看、添加或修改配置文件中的代理节点",
                        onClick = {
                            editProxiesProfileId = targetProfile.id
                            menuProfileId = null
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.ContentCopy,
                        title = "复制链接",
                        subtitle = "复制此配置的订阅或节点 URL",
                        onClick = {
                            copyToClipboard(context, "profile_url", targetProfile.url, "已复制订阅链接至剪贴板")
                            menuProfileId = null
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.Refresh,
                        title = "更新订阅",
                        subtitle = "从远端重新拉取节点和规则",
                        enabled = targetProfile.id !in busyProfileIds,
                        onClick = {
                            onUpdateProfile(targetProfile.id)
                            menuProfileId = null
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.SaveAlt,
                        title = "导出 YAML",
                        subtitle = "保存当前配置文件到本地存储或网盘",
                        onClick = {
                            pendingExportProfileId = targetProfile.id
                            val safeName = targetProfile.name
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                .ifBlank { "clash-profile" }
                            menuProfileId = null
                            exportLauncher.launch("$safeName.yaml")
                        }
                    )

                    var hasBackup by remember(targetProfile.id) { mutableStateOf(false) }
                    LaunchedEffect(targetProfile.id) {
                        hasBackup = hasProfileBackup(targetProfile.id)
                    }
                    if (hasBackup) {
                        ContextMenuItem(
                            icon = Icons.Default.History,
                            title = "恢复上次备份",
                            subtitle = "一键回滚至上次编辑或更新前的配置版本",
                            tint = Color(0xFFD97706),
                            onClick = {
                                profileToRestoreId = targetProfile.id
                                menuProfileId = null
                            }
                        )
                    }

                    val isBusy = targetProfile.id in busyProfileIds
                    ContextMenuItem(
                        icon = Icons.Default.DeleteOutline,
                        title = "删除",
                        subtitle = if (isBusy) "配置正在更新中，暂不可删除" else "移除此配置及其本地文件",
                        tint = MaterialTheme.colorScheme.error,
                        enabled = !isBusy,
                        onClick = {
                            profileToDeleteId = targetProfile.id
                            menuProfileId = null
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        if (editInfoProfile != null) {
            val p = editInfoProfile
            EditProfileInfoDialog(
                initialName = p.name,
                initialUrl = p.url,
                onDismiss = { editInfoProfileId = null },
                onSave = { newName, newUrl ->
                    onEditProfileInfo(p.id, newName, newUrl)
                    editInfoProfileId = null
                    Toast.makeText(context, "配置信息已更新", Toast.LENGTH_SHORT).show()
                }
            )
        }

        editFileProfile?.let { p ->
            ProfileAsyncEditorHost(
                loadKey = "file:${p.id}",
                loadData = { loadProfileYaml(p.id) },
                onDismiss = { editFileProfileId = null },
                onSave = { updatedContent -> onSaveProfileYaml(p.id, updatedContent) },
                successMessage = "配置文件已保存",
                failureMessage = "配置文件保存失败",
                loadingMessage = "正在读取配置文件..."
            ) { initialContent, isSaving, saveError, handleDismiss, handleSave ->
                EditProfileFileDialog(
                    profileName = p.name,
                    initialContent = initialContent,
                    isSaving = isSaving,
                    saveError = saveError,
                    onDismiss = handleDismiss,
                    onSave = handleSave
                )
            }
        }

        editRulesProfile?.let { p ->
            ProfileAsyncEditorHost(
                loadKey = "rules:${p.id}",
                loadData = { loadProfileRulesData(p.id) },
                onDismiss = { editRulesProfileId = null },
                onSave = { updatedRules -> onSaveProfileRules(p.id, updatedRules) },
                successMessage = "分流规则已更新并生效",
                failureMessage = "分流规则保存失败",
                loadingMessage = "正在解析分流规则..."
            ) { (initialRules, policies), isSaving, saveError, handleDismiss, handleSave ->
                EditRulesDialog(
                    profileName = p.name,
                    initialRules = initialRules,
                    availablePolicies = policies,
                    isSaving = isSaving,
                    saveError = saveError,
                    onDismiss = handleDismiss,
                    onSave = handleSave
                )
            }
        }

        editProxiesProfile?.let { p ->
            ProfileAsyncEditorHost(
                loadKey = "proxies:${p.id}",
                loadData = { loadProfileProxies(p.id) },
                onDismiss = { editProxiesProfileId = null },
                onSave = { updatedProxies -> onSaveProfileProxies(p.id, updatedProxies) },
                successMessage = "节点列表已保存并生效",
                failureMessage = "节点列表保存失败",
                loadingMessage = "正在解析节点列表..."
            ) { initialProxies, isSaving, saveError, handleDismiss, handleSave ->
                EditProxiesDialog(
                    profileName = p.name,
                    initialProxies = initialProxies,
                    isSaving = isSaving,
                    saveError = saveError,
                    onDismiss = handleDismiss,
                    onSave = handleSave
                )
            }
        }
    }
}

private fun List<Profile>.findById(id: String?): Profile? =
    id?.let { targetId -> find { it.id == targetId } }
