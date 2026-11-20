package com.clashflux.android.data

import android.annotation.SuppressLint
import android.content.Context
import com.clashflux.android.core.ClashConfigEditor
import com.clashflux.android.core.NetworkClients
import com.clashflux.android.core.VlessParser
import com.clashflux.android.model.ClashRule
import com.clashflux.android.model.Profile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

data class SubscriptionUserInfo(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestamp: Long = 0L
)

data class ResolvedConfigPayload(
    val yamlContent: String,
    val inferredName: String?,
    val userInfo: SubscriptionUserInfo = SubscriptionUserInfo(),
    val errorMessage: String? = null
)

interface ProfileStore {
    suspend fun loadProfiles(): List<Profile>
    suspend fun selectProfile(profileId: String): Pair<List<Profile>, Profile?>
    suspend fun addProfile(nameInput: String, rawInput: String): Result<Pair<List<Profile>, Profile>>
    suspend fun importLocalYamlProfile(nameInput: String, yamlText: String): Result<Pair<List<Profile>, Profile>>
    suspend fun updateProfile(profileId: String): Result<Pair<List<Profile>, Profile>>
    suspend fun editProfileInfo(profileId: String, name: String, url: String): List<Profile>
    suspend fun deleteProfile(profileId: String): Pair<List<Profile>, Profile?>
    suspend fun readProfileYaml(profileId: String): String
    suspend fun writeProfileYaml(profileId: String, content: String): Profile?
    suspend fun hasProfileBackup(profileId: String): Boolean
    suspend fun restoreProfileBackup(profileId: String): Result<Profile>
}

class ProfileRepository internal constructor(private val baseDir: File) : ProfileStore {

    private constructor(context: Context) : this(context.applicationContext.filesDir)

    private val json = Json { ignoreUnknownKeys = true }
    private val storageMutex = Mutex()

    private val httpClient: OkHttpClient = NetworkClients.baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val profilesIndexFile: File
        get() = File(baseDir, "profiles.json")

    private val profilesDir: File
        get() = File(baseDir, "profiles").apply { mkdirs() }

    override suspend fun loadProfiles(): List<Profile> = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            readProfilesUnlocked()
        }
    }

    suspend fun saveProfiles(list: List<Profile>) = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            writeProfilesUnlocked(list)
        }
    }

    override suspend fun selectProfile(profileId: String): Pair<List<Profile>, Profile?> = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val current = readProfilesUnlocked()
            val updated = current.map { it.copy(isSelected = it.id == profileId) }
            writeProfilesUnlocked(updated)
            updated to updated.find { it.isSelected }
        }
    }

    override suspend fun addProfile(nameInput: String, rawInput: String): Result<Pair<List<Profile>, Profile>> =
        withContext(Dispatchers.IO) {
            val cleanInput = ClashConfigEditor.sanitizeYaml(rawInput).trim()
            if (cleanInput.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("请输入有效的订阅或节点链接"))
            }

            val cleanNameInput = stripSingleLineControl(nameInput)
            val resolved = fetchAndResolveConfig(cleanInput, cleanNameInput.ifBlank { "订阅配置" })
            if (resolved.yamlContent.isBlank()) {
                val msg = resolved.errorMessage ?: "未能解析出有效代理节点，请检查链接或 YAML 格式"
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val finalName = cleanNameInput.ifBlank {
                resolved.inferredName ?: deriveNameFromInput(cleanInput)
            }

            storageMutex.withLock {
                persistNewProfileLocked(
                    finalName = finalName,
                    url = cleanInput,
                    yamlContent = resolved.yamlContent,
                    userInfo = resolved.userInfo
                )
            }
        }

    override suspend fun importLocalYamlProfile(nameInput: String, yamlText: String): Result<Pair<List<Profile>, Profile>> =
        withContext(Dispatchers.IO) {
            val sanitized = ClashConfigEditor.sanitizeYaml(yamlText).trim()
            if (sanitized.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("所选文件内容为空"))
            }

            val cleanName = stripSingleLineControl(nameInput).ifBlank { "本地导入配置" }
            val resolvedYaml = VlessParser.parseToClashYaml(sanitized, cleanName).ifBlank {
                val syntaxError = ClashConfigEditor.validateYamlSyntax(sanitized)
                if (syntaxError != null) {
                    return@withContext Result.failure(IllegalArgumentException(syntaxError))
                }
                sanitized
            }

            storageMutex.withLock {
                persistNewProfileLocked(
                    finalName = cleanName,
                    url = "",
                    yamlContent = resolvedYaml
                )
            }
        }

    private fun persistNewProfileLocked(
        finalName: String,
        url: String,
        yamlContent: String,
        userInfo: SubscriptionUserInfo = SubscriptionUserInfo()
    ): Result<Pair<List<Profile>, Profile>> {
        val id = UUID.randomUUID().toString()
        val yamlFile = File(profilesDir, "$id.yaml")
        writeTextAtomic(yamlFile, yamlContent)
        if (!yamlFile.exists() || yamlFile.length() == 0L) {
            return Result.failure(IllegalStateException("写入配置文件失败"))
        }

        val newProfile = Profile(
            id = id,
            name = finalName,
            url = url,
            filePath = yamlFile.absolutePath,
            updatedAt = System.currentTimeMillis(),
            uploadBytes = userInfo.uploadBytes,
            downloadBytes = userInfo.downloadBytes,
            totalBytes = userInfo.totalBytes,
            expireTimestamp = userInfo.expireTimestamp,
            isSelected = true
        )

        val current = readProfilesUnlocked()
        val updated = current.map { it.copy(isSelected = false) } + newProfile
        writeProfilesUnlocked(updated)
        return Result.success(updated to newProfile)
    }

    override suspend fun updateProfile(profileId: String): Result<Pair<List<Profile>, Profile>> =
        withContext(Dispatchers.IO) {
            val target = storageMutex.withLock {
                readProfilesUnlocked().find { it.id == profileId }
            } ?: return@withContext Result.failure(IllegalStateException("未找到该配置"))

            val cleanUrl = ClashConfigEditor.sanitizeYaml(target.url).trim()
            if (cleanUrl.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("订阅链接为空，无法更新"))
            }

            val resolved = fetchAndResolveConfig(cleanUrl, target.name)
            if (resolved.yamlContent.isBlank()) {
                val msg = resolved.errorMessage ?: "更新未获取到有效节点"
                return@withContext Result.failure(IllegalStateException(msg))
            }

            storageMutex.withLock {
                val yamlFile = File(target.filePath).also { it.parentFile?.mkdirs() }
                val existingCustomRules = if (yamlFile.exists()) {
                    runCatching { ClashConfigEditor.parseRules(yamlFile.readText()).filter { it.isCustom } }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val finalYaml = ClashConfigEditor.mergeCustomRules(resolved.yamlContent, existingCustomRules)
                createBackupIfPresent(yamlFile, finalYaml)
                writeTextAtomic(yamlFile, finalYaml)

                val hasUserInfo = resolved.userInfo != SubscriptionUserInfo()
                val current = readProfilesUnlocked()
                val updated = current.map { profile ->
                    if (profile.id == profileId) {
                        profile.copy(
                            updatedAt = System.currentTimeMillis(),
                            uploadBytes = if (hasUserInfo) resolved.userInfo.uploadBytes else profile.uploadBytes,
                            downloadBytes = if (hasUserInfo) resolved.userInfo.downloadBytes else profile.downloadBytes,
                            totalBytes = if (hasUserInfo) resolved.userInfo.totalBytes else profile.totalBytes,
                            expireTimestamp = if (resolved.userInfo.expireTimestamp > 0L) {
                                resolved.userInfo.expireTimestamp
                            } else {
                                profile.expireTimestamp
                            }
                        )
                    } else {
                        profile
                    }
                }
                writeProfilesUnlocked(updated)
                val refreshedTarget = updated.find { it.id == profileId } ?: target
                Result.success(updated to refreshedTarget)
            }
        }

    override suspend fun editProfileInfo(profileId: String, name: String, url: String): List<Profile> =
        withContext(Dispatchers.IO) {
            val cleanName = stripSingleLineControl(name).ifBlank { "订阅配置" }
            val cleanUrl = ClashConfigEditor.sanitizeYaml(url).trim()
            storageMutex.withLock {
                val updated = readProfilesUnlocked().map {
                    if (it.id == profileId) it.copy(name = cleanName, url = cleanUrl) else it
                }
                writeProfilesUnlocked(updated)
                updated
            }
        }

    override suspend fun deleteProfile(profileId: String): Pair<List<Profile>, Profile?> =
        withContext(Dispatchers.IO) {
            storageMutex.withLock {
                val current = readProfilesUnlocked()
                val target = current.find { it.id == profileId }
                if (target != null) {
                    val file = File(target.filePath)
                    runCatching { file.delete() }
                    runCatching { backupFileFor(file).delete() }
                }

                val remaining = current.filterNot { it.id == profileId }
                val finalUpdated = if (remaining.isNotEmpty() && remaining.none { it.isSelected }) {
                    remaining.mapIndexed { idx, p -> p.copy(isSelected = idx == 0) }
                } else {
                    remaining
                }
                writeProfilesUnlocked(finalUpdated)
                finalUpdated to finalUpdated.find { it.isSelected }
            }
        }

    override suspend fun readProfileYaml(profileId: String): String = withContext(Dispatchers.IO) {
        val profile = loadProfiles().find { it.id == profileId } ?: return@withContext ""
        val file = File(profile.filePath)
        if (file.exists()) ClashConfigEditor.sanitizeYaml(file.readText()) else ""
    }

    override suspend fun writeProfileYaml(profileId: String, content: String): Profile? = withContext(Dispatchers.IO) {
        val sanitized = ClashConfigEditor.sanitizeYaml(content)
        storageMutex.withLock {
            val list = readProfilesUnlocked()
            val profile = list.find { it.id == profileId } ?: return@withContext null
            val file = File(profile.filePath).also { it.parentFile?.mkdirs() }
            createBackupIfPresent(file, sanitized)
            writeTextAtomic(file, sanitized)
            profile
        }
    }

    override suspend fun hasProfileBackup(profileId: String): Boolean = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val profile = readProfilesUnlocked().find { it.id == profileId } ?: return@withLock false
            val bak = backupFileFor(File(profile.filePath))
            bak.exists() && bak.length() > 0L
        }
    }

    override suspend fun restoreProfileBackup(profileId: String): Result<Profile> = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val profile = readProfilesUnlocked().find { it.id == profileId }
                ?: return@withLock Result.failure(IllegalStateException("未找到该配置"))
            val target = File(profile.filePath)
            val bak = backupFileFor(target)
            if (!bak.exists() || bak.length() == 0L) {
                return@withLock Result.failure(IllegalStateException("暂无可恢复的备份文件"))
            }
            val backupYaml = ClashConfigEditor.sanitizeYaml(bak.readText())
            val syntaxErr = ClashConfigEditor.validateYamlSyntax(backupYaml)
            if (syntaxErr != null) {
                return@withLock Result.failure(IllegalStateException("备份文件校验失败: $syntaxErr"))
            }
            writeTextAtomic(target, backupYaml)
            runCatching { bak.delete() }
            Result.success(profile)
        }
    }

    /**
     * Resolves an input URL / node URI / YAML text into validated Clash YAML + optional metadata.
     * Shared by both [addProfile] and [updateProfile].
     */
    private fun fetchAndResolveConfig(input: String, defaultName: String): ResolvedConfigPayload {
        if (VlessParser.isNodeUri(input)) {
            return ResolvedConfigPayload(
                yamlContent = VlessParser.parseToClashYaml(input, defaultName),
                inferredName = null
            )
        }

        if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
            return try {
                val request = Request.Builder()
                    .url(input)
                    .header("User-Agent", "ClashMeta/1.18.0 ClashFlux/1.0.0 clash")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return ResolvedConfigPayload(
                            yamlContent = "",
                            inferredName = null,
                            errorMessage = "订阅请求失败: HTTP ${response.code}"
                        )
                    }

                    val body = readBoundedUtf8(response.body).getOrElse { err ->
                        return ResolvedConfigPayload(
                            yamlContent = "",
                            inferredName = null,
                            errorMessage = err.message ?: "读取订阅响应失败"
                        )
                    }
                    var yaml = VlessParser.parseToClashYaml(body, defaultName)
                    if (yaml.isEmpty() && VlessParser.isClashYaml(body)) {
                        yaml = ClashConfigEditor.sanitizeYaml(body)
                    }

                    val userInfo = parseSubscriptionUserInfo(response.header("Subscription-Userinfo"))
                    val headerName = parseContentDispositionName(response.header("Content-Disposition"))
                    ResolvedConfigPayload(
                        yamlContent = yaml,
                        inferredName = headerName,
                        userInfo = userInfo,
                        errorMessage = if (yaml.isBlank()) "订阅内容不包含有效的节点或 Clash 配置" else null
                    )
                }
            } catch (e: Exception) {
                ResolvedConfigPayload(
                    yamlContent = "",
                    inferredName = null,
                    errorMessage = "网络错误: ${e.message}"
                )
            }
        }

        return ResolvedConfigPayload(
            yamlContent = VlessParser.parseToClashYaml(input, defaultName),
            inferredName = null
        )
    }

    private fun readProfilesUnlocked(): List<Profile> {
        val file = profilesIndexFile
        if (!file.exists()) return emptyList()
        return try {
            val raw = file.readText()
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<Profile>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeProfilesUnlocked(list: List<Profile>) {
        writeTextAtomic(profilesIndexFile, json.encodeToString(list))
    }

    private fun backupFileFor(target: File): File = File(target.parentFile, "${target.name}.bak")

    private fun createBackupIfPresent(target: File, nextContent: String) {
        if (!target.exists() || target.length() == 0L) return
        val existing = runCatching { target.readText() }.getOrNull() ?: return
        if (existing.isNotBlank() && existing != nextContent) {
            runCatching { writeTextAtomic(backupFileFor(target), existing) }
        }
    }

    private fun readBoundedUtf8(body: ResponseBody?, maxBytes: Long = MAX_SUBSCRIPTION_BYTES): Result<String> {
        if (body == null) return Result.success("")
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            return Result.failure(IllegalArgumentException("订阅响应超过 ${maxBytes / (1024 * 1024)}MB 大小限制"))
        }
        return runCatching {
            body.byteStream().use { input ->
                readBoundedUtf8Stream(input, maxBytes = maxBytes, errorLabel = "订阅响应")
            }
        }
    }

    companion object {
        const val MAX_SUBSCRIPTION_BYTES = 16L * 1024L * 1024L

        fun readBoundedUtf8Stream(
            stream: InputStream,
            maxBytes: Long = MAX_SUBSCRIPTION_BYTES,
            errorLabel: String = "配置文件"
        ): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                if (total > maxBytes) {
                    throw IllegalArgumentException("${errorLabel}超过 ${maxBytes / (1024 * 1024)}MB 大小限制")
                }
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        fun writeTextAtomic(target: File, content: String) {
            val parent = target.parentFile
            parent?.mkdirs()
            val tmp = File(parent, "${target.name}.tmp")
            tmp.writeText(content)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                try {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    runCatching { tmp.delete() }
                    throw e
                }
            }
        }
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ProfileRepository? = null

        fun getInstance(context: Context): ProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: ProfileRepository(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Parses `Subscription-Userinfo` headers such as:
         * `upload=1024; download=2048; total=10737418240; expire=1790000000`
         * Normalizes [expireTimestamp] to epoch milliseconds cleanly.
         */
        fun parseSubscriptionUserInfo(header: String?): SubscriptionUserInfo {
            if (header.isNullOrBlank()) return SubscriptionUserInfo()
            var upload = 0L
            var download = 0L
            var total = 0L
            var expire = 0L

            header.split(";").forEach { part ->
                val eqIdx = part.indexOf('=')
                if (eqIdx > 0) {
                    val key = part.substring(0, eqIdx).trim().lowercase()
                    val value = part.substring(eqIdx + 1).trim().toLongOrNull() ?: 0L
                    when (key) {
                        "upload" -> upload = value.coerceAtLeast(0L)
                        "download" -> download = value.coerceAtLeast(0L)
                        "total" -> total = value.coerceAtLeast(0L)
                        "expire" -> {
                            expire = when {
                                value <= 0L -> 0L
                                value < 100_000_000_000L -> value * 1000L
                                else -> value
                            }
                        }
                    }
                }
            }
            return SubscriptionUserInfo(
                uploadBytes = upload,
                downloadBytes = download,
                totalBytes = total,
                expireTimestamp = expire
            )
        }

        fun deriveNameFromInput(rawInput: String): String {
            val clean = rawInput.trim()
            if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
                return try {
                    URI(clean).host?.takeIf { it.isNotBlank() } ?: "远程订阅"
                } catch (_: Exception) {
                    "远程订阅"
                }
            }
            if (VlessParser.isNodeUri(clean)) {
                val firstLine = clean.lines().firstOrNull { it.isNotBlank() } ?: ""
                val hashPart = firstLine.substringAfter('#', "").trim()
                if (hashPart.isNotEmpty()) {
                    val decoded = VlessParser.percentDecodeUtf8(hashPart)
                    if (decoded.isNotBlank()) return decoded.take(32)
                }
                return "导入节点"
            }
            return "本地配置"
        }

        private fun parseContentDispositionName(header: String?): String? {
            if (header.isNullOrBlank()) return null
            val filenameStar = Regex("""filename\*\s*=\s*(?:UTF-8''|utf-8'')?([^;\r\n]+)""")
                .find(header)?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')
            val rawName = filenameStar ?: Regex("""filename\s*=\s*"?([^";\r\n]+)"?""")
                .find(header)?.groupValues?.getOrNull(1)?.trim()
            val decoded = rawName?.let { VlessParser.percentDecodeUtf8(it) }
            return decoded?.removeSuffix(".yaml")?.removeSuffix(".yml")?.trim()?.takeIf { it.isNotBlank() }
        }

        private fun stripSingleLineControl(input: String): String {
            return input.filter { it >= ' ' }.trim()
        }

        /**
         * Builds the active runtime configuration for `files/clash/config.yaml` from a user's
         * profile YAML without mutating the source profile file.
         */
        fun prepareRuntimeYaml(
            rawProfileYaml: String,
            bypassLan: Boolean,
            mixedPort: Int = SettingsRepository.DEFAULT_MIXED_PORT,
            httpPort: Int = SettingsRepository.DEFAULT_HTTP_PORT,
            socksPort: Int = SettingsRepository.DEFAULT_SOCKS_PORT
        ): String {
            val mobileCompatible = ClashConfigEditor.ensureMobileRuntimeDefaults(
                yamlContent = rawProfileYaml,
                mixedPort = mixedPort,
                httpPort = httpPort,
                socksPort = socksPort
            )
            var yaml = ClashConfigEditor.normalizeSubscriptionRules(mobileCompatible)
            if (bypassLan && yaml.isNotBlank()) {
                val rules = ClashConfigEditor.parseRules(yaml)
                if (rules.isNotEmpty()) {
                    val lanRules = listOf(
                        ClashRule(type = "IP-CIDR", payload = "192.168.0.0/16", policy = "DIRECT", noResolve = true),
                        ClashRule(type = "IP-CIDR", payload = "10.0.0.0/8", policy = "DIRECT", noResolve = true),
                        ClashRule(type = "IP-CIDR", payload = "172.16.0.0/12", policy = "DIRECT", noResolve = true),
                        ClashRule(type = "IP-CIDR", payload = "169.254.0.0/16", policy = "DIRECT", noResolve = true),
                        ClashRule(type = "GEOIP", payload = "lan", policy = "DIRECT", noResolve = true)
                    )
                    val existingKeys = rules.map { "${it.type.uppercase()}:${it.payload.lowercase()}" }.toSet()
                    val missingLan = lanRules.filter { "${it.type}:${it.payload.lowercase()}" !in existingKeys }
                    if (missingLan.isNotEmpty()) {
                        yaml = ClashConfigEditor.saveRules(yaml, missingLan + rules)
                    }
                }
            }
            return yaml
        }
    }
}
