package com.nekobot.app.data.local.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.security.SecurePreferenceStore

/** 沙箱环境变量的校验与合并；不把变量值写入提示词或工具结果。 */
object SandboxEnvironment {
    const val MAX_VARIABLES = 64
    const val MAX_VALUE_LENGTH = 65536
    private val namePattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    private val protectedNames = setOf(
        "PROOT_TMP_DIR", "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_LOADER_32",
        "NEKOBOT_SESSION_ID", "NEKOBOT_SHARED_DIR", "PS1", "ENV", "BASH_ENV"
    )

    fun isValidName(name: String): Boolean = namePattern.matches(name) &&
        name !in protectedNames && !name.startsWith("__nekobot_")

    fun isValidValue(value: String): Boolean = '\u0000' !in value && value.length <= MAX_VALUE_LENGTH

    fun validate(values: Map<String, String>) {
        require(values.size <= MAX_VARIABLES) { "环境变量最多 $MAX_VARIABLES 个" }
        require(values.keys.all(::isValidName)) { "环境变量名无效或为沙箱保留名称" }
        require(values.values.all(::isValidValue)) { "环境变量值无效或过长" }
    }

    fun merge(global: Map<String, String>, session: Map<String, String>): Map<String, String> = global + session

    /** Room 使用文件名，偏好设置使用档案名；两条链路必须解析到同一个键。 */
    internal fun storageKey(sessionId: String?, dbName: String): String {
        if (sessionId == null) return "sandbox_environment_global"
        val profile = dbName.removeSuffix(".db")
        return "sandbox_environment_session_${profile.length}_${profile}_$sessionId"
    }

    /** 持久 shell 在下一条命令前更新变量；删除后恢复基础值或 unset。 */
    internal fun shellUpdates(
        previous: Map<String, String>,
        current: Map<String, String>,
        defaults: Map<String, String> = emptyMap(),
    ): String {
        val updates = buildString {
            (previous.keys + current.keys).forEach { name ->
                if (previous[name] == current[name]) return@forEach
                val value = current[name] ?: defaults[name]
                if (value == null) append("unset $name\n")
                else append("export $name=${LocalMcpSandbox.localPosixQuote(value)}\n")
            }
        }
        if (updates.isEmpty()) return ""
        // 用户此前打开 set -x 时也不能让内部 export 把密钥回显给 AI。
        return "case \$- in *x*) __nekobot_env_trace=1;; *) __nekobot_env_trace=0;; esac\n" +
            "set +x\n" + updates +
            "if [ \"\$__nekobot_env_trace\" = 1 ]; then set -x; fi\nunset __nekobot_env_trace\n"
    }
}

/** 复用 Keystore 安全存储；会话变量按数据库隔离，全局变量供全部沙箱使用。 */
internal class SandboxEnvironmentStore private constructor(context: Context) {
    private val prefs = PrefsManager(context.applicationContext)
    private val secure = SecurePreferenceStore(context.applicationContext)
    private val gson = Gson()

    private fun key(sessionId: String?, dbName: String): String = SandboxEnvironment.storageKey(sessionId, dbName)

    @Synchronized
    fun read(sessionId: String? = null, dbName: String = prefs.activeDbName): Map<String, String> {
        val raw = secure.getString(key(sessionId, dbName)) ?: return emptyMap()
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.entrySet().associate { (name, value) ->
                name to value.asString
            }.also(SandboxEnvironment::validate)
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun replace(values: Map<String, String>, sessionId: String? = null, dbName: String = prefs.activeDbName) {
        SandboxEnvironment.validate(values)
        secure.putString(key(sessionId, dbName), values.takeIf { it.isNotEmpty() }?.let(gson::toJson))
    }

    @Synchronized
    fun update(values: Map<String, String>, sessionId: String? = null, dbName: String = prefs.activeDbName) {
        replace(read(sessionId, dbName) + values, sessionId, dbName)
    }

    @Synchronized
    fun resolved(sessionId: String? = null): Map<String, String> = SandboxEnvironment.merge(
        read(), sessionId?.let { read(it) }.orEmpty()
    )

    companion object {
        @Volatile private var instance: SandboxEnvironmentStore? = null

        fun get(context: Context): SandboxEnvironmentStore = instance ?: synchronized(this) {
            instance ?: SandboxEnvironmentStore(context).also { instance = it }
        }
    }
}
