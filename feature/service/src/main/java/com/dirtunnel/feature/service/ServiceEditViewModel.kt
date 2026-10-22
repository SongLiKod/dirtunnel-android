package com.dirtunnel.feature.service

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.engine.process.PortChecker
import com.dirtunnel.core.storage.dao.FrpProfileDao
import com.dirtunnel.core.storage.dao.ServiceDao
import com.dirtunnel.core.storage.dao.TemplateDao
import com.dirtunnel.core.storage.data.SecurityStore
import com.dirtunnel.core.storage.entity.FrpProfileEntity
import com.dirtunnel.core.storage.entity.ServiceInstanceEntity
import com.dirtunnel.core.storage.entity.ServiceTemplateEntity
import com.dirtunnel.core.storage.model.AccessPermission
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC
import com.dirtunnel.core.storage.model.ProtocolType
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.ui.components.AppleAlert
import com.dirtunnel.feature.service.runtime.ServiceRuntimeController
import com.dirtunnel.feature.service.runtime.StartResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 服务编辑表单（REQ 4.2 全部实例属性）。 */
data class ServiceForm(
    val id: Long = 0L,
    val directoryId: Long = 0L,
    val name: String = "",
    val protocol: ProtocolType = ProtocolType.HTTP,
    val port: String = ProtocolType.HTTP.defaultPort.toString(),
    val username: String = "",
    val password: String = "",
    val passwordTouched: Boolean = false,
    val anonymous: Boolean = false,
    val permission: AccessPermission = AccessPermission.READ_ONLY,
    val enableTunnel: Boolean = false,
    val tunnelProfileId: Long = PROFILE_PUBLIC,
    val autoStart: Boolean = false,
    val whitelist: String = "",
    val rateLimitKbps: String = "",
    val maxConnections: String = "",
    val useCustomCert: Boolean = false,
    val isNew: Boolean = true,
)

/**
 * 服务实例 CRUD + 校验（REQ 4.2；REQ 4.4 端口自定义；异常提示必带解决方案）。
 */
@HiltViewModel
class ServiceEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serviceDao: ServiceDao,
    private val templateDao: TemplateDao,
    private val frpProfileDao: FrpProfileDao,
    private val security: SecurityStore,
    private val settingsStore: SettingsStore,
    private val runtime: ServiceRuntimeController,
) : ViewModel() {

    // 路由 NavType.LongType 存入的是 Long，按 Any 读取后解析，避免 ClassCastException
    private val serviceId: Long =
        savedStateHandle.get<Any>("serviceId")?.toString()?.toLongOrNull() ?: 0L
    private val directoryId: Long =
        savedStateHandle.get<Any>("directoryId")?.toString()?.toLongOrNull() ?: 0L

    private val _form = MutableStateFlow(ServiceForm(directoryId = directoryId))
    val form: StateFlow<ServiceForm> = _form.asStateFlow()

    private val _alert = MutableStateFlow<AppleAlert?>(null)
    val alert: StateFlow<AppleAlert?> = _alert.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    val templates: StateFlow<List<ServiceTemplateEntity>> = templateDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 自建 FRP 档案（隧道归属选择，REQ 4.3 两种模式）。 */
    val tunnelProfiles: StateFlow<List<FrpProfileEntity>> = frpProfileDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** V1.2 高阶协议（Rsync/NFS）默认隐藏开关。 */
    val advancedVisible: StateFlow<Boolean> = settingsStore.settings
        .map { it.advancedFeaturesVisible }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _portCheck = MutableStateFlow<String?>(null)
    val portCheck: StateFlow<String?> = _portCheck.asStateFlow()

    init {
        viewModelScope.launch {
            if (serviceId != 0L) {
                val entity = serviceDao.getById(serviceId) ?: return@launch
                _form.value = ServiceForm(
                    id = entity.id,
                    directoryId = entity.directoryId,
                    name = entity.name,
                    protocol = entity.protocolType,
                    port = entity.port.toString(),
                    username = entity.username,
                    password = security.servicePassword(entity.id) ?: "",
                    passwordTouched = false,
                    anonymous = entity.anonymous,
                    permission = entity.accessPermission,
                    enableTunnel = entity.enableTunnel,
                    tunnelProfileId = entity.tunnelProfileId,
                    autoStart = entity.autoStart,
                    whitelist = entity.whitelist,
                    rateLimitKbps = if (entity.rateLimitKbps > 0) entity.rateLimitKbps.toString() else "",
                    maxConnections = if (entity.maxConnections > 0) entity.maxConnections.toString() else "",
                    useCustomCert = entity.useCustomCert,
                    isNew = false,
                )
            } else {
                _form.value = _form.value.copy(
                    name = "HTTP 服务",
                    port = ProtocolType.HTTP.defaultPort.toString(),
                )
            }
        }
    }

    // ===== 表单更新 =====

    fun update(transform: (ServiceForm) -> ServiceForm) {
        _form.value = transform(_form.value)
    }

    fun setProtocol(p: ProtocolType) = update {
        it.copy(protocol = p, port = if (it.isNew || it.port == it.protocol.defaultPort.toString()) p.defaultPort.toString() else it.port)
    }

    fun setPermission(p: AccessPermission) = update { it.copy(permission = p) }

    /** 端口即时校验（TECH 七.3 启动前扫描端口 + 提示更换端口）。 */
    fun checkPort() {
        val f = _form.value
        val port = f.port.toIntOrNull()
        viewModelScope.launch {
            if (port == null || port !in 1..65535) {
                _portCheck.value = "端口必须是 1~65535 的整数。方案：填写有效端口，例如 ${f.protocol.defaultPort}。"
                return@launch
            }
            if (PortChecker.isPrivileged(port)) {
                _portCheck.value = PortChecker.privilegedSolution(port)
                return@launch
            }
            val takenByOther = withContext(Dispatchers.IO) { serviceDao.getByPort(port, excludeId = f.id) }
            if (takenByOther != null) {
                _portCheck.value = "端口 $port 已被本应用服务「${takenByOther.name}」使用。方案：更换端口。"
                return@launch
            }
            val available = withContext(Dispatchers.IO) { PortChecker.isAvailable(port) }
            _portCheck.value = if (available) null else PortChecker.occupySolution(port)
        }
    }

    // ===== 保存 / 删除 =====

    fun save() {
        viewModelScope.launch {
            val f = _form.value
            // 校验（每条失败提示都附解决方案）
            if (f.name.isBlank()) {
                _alert.value = AppleAlert("名称不能为空", "方案：为该服务取一个便于识别的名称，如“照片共享”。")
                return@launch
            }
            val port = f.port.toIntOrNull()
            if (port == null || port !in 1..65535) {
                _alert.value = AppleAlert("端口无效", "方案：填写 1~65535 的端口，默认建议 ${f.protocol.defaultPort}。")
                return@launch
            }
            if (PortChecker.isPrivileged(port)) {
                _alert.value = AppleAlert("特权端口无法绑定", PortChecker.privilegedSolution(port))
                return@launch
            }
            val conflict = withContext(Dispatchers.IO) { serviceDao.getByPort(port, excludeId = f.id) }
            if (conflict != null) {
                _alert.value = AppleAlert(
                    "端口冲突",
                    "端口 $port 已被服务「${conflict.name}」占用。方案：更换端口，或先停止该服务。",
                )
                return@launch
            }
            if (!f.anonymous && f.username.isBlank()) {
                _alert.value = AppleAlert(
                    "未填写账号",
                    "所有服务默认禁止匿名访问（安全设计）。方案：填写登录账号，或手动开启“匿名访问”。",
                )
                return@launch
            }

            val existing = if (f.id != 0L) serviceDao.getById(f.id) else null
            // 若服务正在运行，保存前先停止（端口/参数即时生效）
            if (existing != null) {
                withContext(Dispatchers.IO) { runtime.stop(existing.id) }
            }

            val entity = ServiceInstanceEntity(
                id = f.id,
                directoryId = if (f.directoryId != 0L) f.directoryId else directoryId,
                name = f.name.trim(),
                protocol = f.protocol.name,
                port = port,
                username = f.username.trim(),
                anonymous = f.anonymous,
                permission = f.permission.name,
                enableTunnel = f.enableTunnel,
                tunnelProfileId = if (f.enableTunnel) f.tunnelProfileId else PROFILE_PUBLIC,
                autoStart = f.autoStart,
                whitelist = f.whitelist,
                rateLimitKbps = f.rateLimitKbps.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                maxConnections = f.maxConnections.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                useCustomCert = f.useCustomCert,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )

            val newId = if (f.id == 0L) {
                serviceDao.insert(entity)
            } else {
                serviceDao.update(entity)
                f.id
            }
            // 密码：仅在用户修改时写入加密存储（TECH 六.1）
            if (!f.anonymous) {
                val pwd = f.password
                if (f.isNew || f.passwordTouched) {
                    if (pwd.isNotBlank()) {
                        security.setServicePassword(newId, pwd)
                    } else if (security.servicePassword(newId) == null) {
                        _alert.value = AppleAlert(
                            "未设置密码",
                            "方案：填写密码后再保存；或开启“匿名访问”。本次已保存配置但服务无法启动。",
                        )
                    }
                } else if (security.servicePassword(newId) == null && pwd.isNotBlank()) {
                    security.setServicePassword(newId, pwd)
                }
            }
            _saved.value = true
        }
    }

    fun delete() {
        viewModelScope.launch {
            val f = _form.value
            if (f.id == 0L) {
                _deleted.value = true
                return@launch
            }
            withContext(Dispatchers.IO) { runtime.stop(f.id) }
            serviceDao.getById(f.id)?.let { serviceDao.delete(it) }
            security.setServicePassword(f.id, null)
            _deleted.value = true
        }
    }

    // ===== 配置模板（REQ 六 · V1.2 配置模板） =====

    fun applyTemplate(template: ServiceTemplateEntity) = update {
        it.copy(
            protocol = ProtocolType.fromName(template.protocol),
            port = template.port.toString(),
            username = template.username,
            anonymous = template.anonymous,
            permission = AccessPermission.fromName(template.permission),
            enableTunnel = template.enableTunnel,
            whitelist = template.whitelist,
            rateLimitKbps = if (template.rateLimitKbps > 0) template.rateLimitKbps.toString() else "",
            maxConnections = if (template.maxConnections > 0) template.maxConnections.toString() else "",
        )
    }

    fun saveAsTemplate(name: String) {
        val f = _form.value
        viewModelScope.launch {
            templateDao.insert(
                ServiceTemplateEntity(
                    name = name.ifBlank { "${f.protocol.label} 模板" },
                    protocol = f.protocol.name,
                    port = f.port.toIntOrNull() ?: f.protocol.defaultPort,
                    username = f.username,
                    anonymous = f.anonymous,
                    permission = f.permission.name,
                    enableTunnel = f.enableTunnel,
                    whitelist = f.whitelist,
                    rateLimitKbps = f.rateLimitKbps.toIntOrNull() ?: 0,
                    maxConnections = f.maxConnections.toIntOrNull() ?: 0,
                ),
            )
            _alert.value = AppleAlert("模板已保存", "可在其他服务的“从模板应用”中一键复用（不含密码）。")
        }
    }

    fun deleteTemplate(id: Long) {
        viewModelScope.launch { templateDao.deleteById(id) }
    }

    fun startAndReturn(): Boolean {
        // 保存成功后由 UI 触发启动（可选）
        return _saved.value
    }

    fun dismissAlert() {
        val confirm = _alert.value?.onConfirm
        _alert.value = null
        confirm?.invoke()
    }

    fun consumeSaved() {
        _saved.value = false
    }

    fun consumeDeleted() {
        _deleted.value = false
    }

    fun clearPortCheck() {
        _portCheck.value = null
    }
}
