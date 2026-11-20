package com.clashflux.android.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.theme.clashFluxSwitchColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProxyDetailEditDialog(
    initialProxy: Map<String, Any>?,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (MutableMap<String, Any>) -> Unit
) {
    val initialType = initialProxy?.get("type")?.toString()?.lowercase() ?: "vless"
    val initialWsOpts = initialProxy?.get("ws-opts") as? Map<*, *>
    val initialWsHeaders = initialWsOpts?.get("headers") as? Map<*, *>
    val initialGrpcOpts = initialProxy?.get("grpc-opts") as? Map<*, *>

    var name by remember { mutableStateOf(initialProxy?.get("name")?.toString() ?: "") }
    var type by remember { mutableStateOf(initialType) }
    var server by remember { mutableStateOf(initialProxy?.get("server")?.toString() ?: "") }
    var port by remember {
        mutableStateOf(
            initialProxy?.get("port")?.toString()
                ?: defaultPortForType(initialType).toString()
        )
    }
    var credential by remember {
        mutableStateOf(
            when (initialType) {
                "tuic" -> initialProxy?.get("uuid")?.toString() ?: ""
                "vless", "vmess" -> initialProxy?.get("uuid")?.toString() ?: ""
                else -> initialProxy?.get("password")?.toString()
                    ?: initialProxy?.get("uuid")?.toString()
                    ?: ""
            }
        )
    }
    var secondarySecret by remember {
        mutableStateOf(
            if (initialType == "tuic") initialProxy?.get("password")?.toString() ?: "" else ""
        )
    }
    var username by remember { mutableStateOf(initialProxy?.get("username")?.toString() ?: "") }
    var cipher by remember {
        mutableStateOf(
            initialProxy?.get("cipher")?.toString()
                ?: if (initialType == "vmess") "auto" else "aes-256-gcm"
        )
    }
    var alterId by remember { mutableStateOf(initialProxy?.get("alterId")?.toString() ?: "0") }
    var network by remember { mutableStateOf(initialProxy?.get("network")?.toString()?.lowercase() ?: "tcp") }
    var wsPath by remember { mutableStateOf(initialWsOpts?.get("path")?.toString() ?: "") }
    var wsHost by remember {
        mutableStateOf(
            (initialWsHeaders?.get("Host") ?: initialWsHeaders?.get("host"))?.toString() ?: ""
        )
    }
    var grpcServiceName by remember {
        mutableStateOf(initialGrpcOpts?.get("grpc-service-name")?.toString() ?: "")
    }
    var sni by remember {
        mutableStateOf(
            initialProxy?.get("servername")?.toString()
                ?: initialProxy?.get("sni")?.toString()
                ?: ""
        )
    }
    var tlsEnabled by remember {
        mutableStateOf(
            (initialProxy?.get("tls") as? Boolean)
                ?: (initialType in setOf("trojan", "hysteria2", "tuic"))
        )
    }
    var skipCertVerify by remember {
        mutableStateOf((initialProxy?.get("skip-cert-verify") as? Boolean) ?: false)
    }
    var udpEnabled by remember {
        mutableStateOf((initialProxy?.get("udp") as? Boolean) ?: true)
    }
    var validationError by remember { mutableStateOf<String?>(null) }
    var typeExpanded by remember { mutableStateOf(false) }

    val normalizedType = type.trim().lowercase()
    val supportedTypes = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "tuic", "socks5", "http")
    val ssPresetCiphers = listOf(
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm"
    )
    val transportOptions = remember(network) {
        val base = mutableListOf("tcp", "ws", "grpc")
        val cleanNet = network.trim().lowercase()
        if (cleanNet.isNotEmpty() && cleanNet !in base) base.add(cleanNet)
        base
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isNew) "添加代理节点" else "编辑代理节点",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("节点名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = normalizedType.uppercase(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("协议类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        supportedTypes.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.uppercase()) },
                                onClick = {
                                    type = item
                                    if (item in setOf("trojan", "hysteria2", "tuic")) {
                                        tlsEnabled = true
                                    }
                                    if (item == "vmess" && cipher == "aes-256-gcm") {
                                        cipher = "auto"
                                    } else if (item == "ss" && cipher == "auto") {
                                        cipher = "aes-256-gcm"
                                    }
                                    if (isNew) {
                                        port = defaultPortForType(item).toString()
                                    }
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = {
                            server = it
                            validationError = null
                        },
                        label = { Text("服务器地址 *") },
                        singleLine = true,
                        modifier = Modifier.weight(0.68f)
                    )

                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it
                            validationError = null
                        },
                        label = { Text("端口 *") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.32f)
                    )
                }

                if (normalizedType in setOf("socks5", "http")) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            validationError = null
                        },
                        label = { Text("用户名 (可选)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (normalizedType != "wireguard") {
                    OutlinedTextField(
                        value = credential,
                        onValueChange = {
                            credential = it
                            validationError = null
                        },
                        label = {
                            val labelText = when (normalizedType) {
                                "vless", "vmess", "tuic" -> "UUID *"
                                "socks5", "http" -> "密码 (可选)"
                                else -> "密码 *"
                            }
                            Text(labelText)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (normalizedType == "tuic") {
                    OutlinedTextField(
                        value = secondarySecret,
                        onValueChange = { secondarySecret = it },
                        label = { Text("密码 (TUIC v5 可选)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (normalizedType == "ss" || normalizedType == "vmess") {
                    OutlinedTextField(
                        value = cipher,
                        onValueChange = {
                            cipher = it
                            validationError = null
                        },
                        label = { Text(if (normalizedType == "ss") "加密方式 (Cipher) *" else "加密方式 (Cipher)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (normalizedType == "ss") {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ssPresetCiphers.forEach { preset ->
                                FilterChip(
                                    selected = cipher.equals(preset, ignoreCase = true),
                                    onClick = { cipher = preset },
                                    label = { Text(preset, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                if (normalizedType == "vmess") {
                    OutlinedTextField(
                        value = alterId,
                        onValueChange = {
                            alterId = it.filter { ch -> ch.isDigit() }
                            validationError = null
                        },
                        label = { Text("AlterId") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (normalizedType in setOf("vless", "vmess", "trojan")) {
                    Text("传输方式 (Network)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        transportOptions.forEach { option ->
                            FilterChip(
                                selected = network.equals(option, ignoreCase = true),
                                onClick = { network = option },
                                label = { Text(option.uppercase(), fontSize = 11.sp) }
                            )
                        }
                    }

                    if (network.equals("ws", ignoreCase = true)) {
                        OutlinedTextField(
                            value = wsPath,
                            onValueChange = { wsPath = it },
                            label = { Text("WebSocket Path (如 /ws)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = wsHost,
                            onValueChange = { wsHost = it },
                            label = { Text("WebSocket Host 请求头 (可选)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (network.equals("grpc", ignoreCase = true)) {
                        OutlinedTextField(
                            value = grpcServiceName,
                            onValueChange = { grpcServiceName = it },
                            label = { Text("gRPC Service Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                OutlinedTextField(
                    value = sni,
                    onValueChange = { sni = it },
                    label = { Text("SNI / Servername (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用 TLS", fontSize = 13.sp)
                    Switch(
                        checked = tlsEnabled,
                        onCheckedChange = { tlsEnabled = it },
                        colors = clashFluxSwitchColors()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("跳过证书验证 (insecure)", fontSize = 13.sp)
                    Switch(
                        checked = skipCertVerify,
                        onCheckedChange = { skipCertVerify = it },
                        colors = clashFluxSwitchColors()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用 UDP 转发", fontSize = 13.sp)
                    Switch(
                        checked = udpEnabled,
                        onCheckedChange = { udpEnabled = it },
                        colors = clashFluxSwitchColors()
                    )
                }

                if (validationError != null) {
                    Text(
                        text = validationError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val formInput = ProxyFormInput(
                        name = name,
                        type = type,
                        server = server,
                        port = port,
                        credential = credential,
                        secondarySecret = secondarySecret,
                        username = username,
                        cipher = cipher,
                        alterId = alterId,
                        network = network,
                        wsPath = wsPath,
                        wsHost = wsHost,
                        grpcServiceName = grpcServiceName,
                        sni = sni,
                        tlsEnabled = tlsEnabled,
                        skipCertVerify = skipCertVerify,
                        udpEnabled = udpEnabled
                    )
                    buildEditedProxyMap(formInput, initialProxy)
                        .onSuccess(onConfirm)
                        .onFailure { validationError = it.message }
                },
                colors = ButtonDefaults.buttonColors(containerColor = VergePrimary)
            ) {
                Text("确定", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

