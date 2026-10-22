package com.dirtunnel.feature.tunnel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.storage.dao.ForwardDao
import com.dirtunnel.core.storage.dao.FrpProfileDao
import com.dirtunnel.core.storage.entity.TcpForwardEntity
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleButton
import com.dirtunnel.core.ui.components.AppleButtonSize
import com.dirtunnel.core.ui.components.AppleButtonStyle
import com.dirtunnel.core.ui.components.AppleCell
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleOption
import com.dirtunnel.core.ui.components.AppleOptionSheet
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleSectionHeader
import com.dirtunnel.core.ui.components.AppleSwitchCell
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.AppleTextField
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.appleColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** TCP 转发表单（REQ 三 · 穿透层独立能力：自定义本地 TCP 端口转发）。 */
data class TcpForwardForm(
    val id: Long = 0L,
    val name: String = "",
    val localPort: String = "",
    val remotePort: String = "",
    val profileId: Long = PROFILE_PUBLIC,
    val enabled: Boolean = true,
    val isNew: Boolean = true,
)

@HiltViewModel
class TcpForwardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val forwardDao: ForwardDao,
    private val frpProfileDao: FrpProfileDao,
) : ViewModel() {

    private val forwardId: Long =
        // 路由 NavType.LongType 存入的是 Long，按 Any 读取后解析，避免 ClassCastException
        savedStateHandle.get<Any>("forwardId")?.toString()?.toLongOrNull() ?: 0L

    private val _form = MutableStateFlow(TcpForwardForm())
    val form: StateFlow<TcpForwardForm> = _form.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val profiles: StateFlow<List<TcpForwardProfileOption>> = frpProfileDao.observeAll()
        .map { list ->
            listOf(TcpForwardProfileOption(PROFILE_PUBLIC, "公共隧道")) +
                list.map { TcpForwardProfileOption(it.id, it.name) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            if (forwardId != 0L) {
                val entity = forwardDao.getById(forwardId) ?: return@launch
                _form.value = TcpForwardForm(
                    id = entity.id,
                    name = entity.name,
                    localPort = entity.localPort.toString(),
                    remotePort = if (entity.remotePort > 0) entity.remotePort.toString() else "",
                    profileId = entity.profileId,
                    enabled = entity.enabled,
                    isNew = false,
                )
            }
        }
    }

    fun update(transform: (TcpForwardForm) -> TcpForwardForm) {
        _form.value = transform(_form.value)
    }

    fun save() {
        viewModelScope.launch {
            val f = _form.value
            val local = f.localPort.trim().toIntOrNull()
            val remote = f.remotePort.trim().toIntOrNull() ?: 0
            if (f.name.isBlank()) {
                _error.value = "请填写转发名称"
                return@launch
            }
            if (local == null || local !in 1..65535) {
                _error.value = "本地端口需为 1-65535 的整数"
                return@launch
            }
            if (remote != 0 && remote !in 1..65535) {
                _error.value = "远端端口需为 1-65535，或留空由服务器分配"
                return@launch
            }
            val entity = TcpForwardEntity(
                id = if (f.isNew) 0L else f.id,
                name = f.name.trim(),
                localPort = local,
                remotePort = remote,
                profileId = f.profileId,
                enabled = f.enabled,
            )
            if (f.isNew) forwardDao.insert(entity) else forwardDao.update(entity)
            _saved.value = true
        }
    }

    fun delete() {
        viewModelScope.launch {
            forwardDao.getById(forwardId)?.let { forwardDao.delete(it) }
            _deleted.value = true
        }
    }

    fun consumeSaved() { _saved.value = false }
    fun consumeDeleted() { _deleted.value = false }
    fun clearError() { _error.value = null }
}

/** 隧道归属选项。 */
data class TcpForwardProfileOption(val id: Long, val label: String)

/**
 * 自定义 TCP 转发编辑（REQ 三 · 穿透层独立能力 + REQ 4.3 隧道兼容 TCP）。
 * 用户可启动 APP 以外的本地服务，通过 DirTunnel 的 FRP 隧道转发出去。
 */
@Composable
fun TcpForwardEditScreen(
    onBack: () -> Unit,
    viewModel: TcpForwardViewModel = hiltViewModel(),
) {
    val hazeState = rememberHazeState()
    val colors = appleColors()

    val form by viewModel.form.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    var showProfileSheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) {
            viewModel.consumeSaved()
            onBack()
        }
    }
    LaunchedEffect(deleted) {
        if (deleted) {
            viewModel.consumeDeleted()
            onBack()
        }
    }

    AppleScreen(
        title = if (form.isNew) "添加 TCP 转发" else "编辑 TCP 转发",
        subtitle = "本地端口 → 公网",
        hazeState = hazeState,
        onBack = onBack,
        actions = {
            AppleButton(
                text = "保存",
                onClick = { viewModel.save() },
                style = AppleButtonStyle.FILLED,
                size = AppleButtonSize.SMALL,
                leadingIcon = Icons.Filled.Save,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                AppleSectionHeader(
                    "转发配置",
                    footer = "本地端口为 APP 以外正在监听的服务（如 MySQL 3306、SSH 22、调试端口）。",
                )
                AppleGroupCard {
                    AppleCell(title = "名称", showSeparator = true) {
                        AppleTextField(
                            value = form.name,
                            onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                            placeholder = "如：本地 MySQL",
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(title = "本地端口", showSeparator = true) {
                        AppleTextField(
                            value = form.localPort,
                            onValueChange = { v -> viewModel.update { it.copy(localPort = v.filter { c -> c.isDigit() }) } },
                            placeholder = "3306",
                            showBorder = false,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(
                        title = "远端端口",
                        subtitle = "留空 = 由服务器随机分配",
                        showSeparator = true,
                    ) {
                        AppleTextField(
                            value = form.remotePort,
                            onValueChange = { v -> viewModel.update { it.copy(remotePort = v.filter { c -> c.isDigit() }) } },
                            placeholder = "随机",
                            showBorder = false,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(
                        title = "隧道归属",
                        value = profiles.firstOrNull { it.id == form.profileId }?.label ?: "公共隧道",
                        showChevron = true,
                        showSeparator = false,
                        onClick = { showProfileSheet = true },
                    )
                }
            }

            item {
                AppleSectionHeader("开关")
                AppleGroupCard {
                    AppleSwitchCell(
                        title = "启用该转发",
                        subtitle = "关闭后不参与隧道启动",
                        checked = form.enabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } },
                        showSeparator = false,
                    )
                }
            }

            if (!form.isNew) {
                item {
                    AppleSectionHeader("危险操作")
                    AppleGroupCard {
                        AppleCell(
                            title = "删除该转发",
                            destructive = true,
                            leadingIcon = Icons.Filled.Delete,
                            leadingIconTint = colors.error,
                            showSeparator = false,
                            onClick = { viewModel.delete() },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    AppleOptionSheet(
        visible = showProfileSheet,
        onDismiss = { showProfileSheet = false },
        title = "选择隧道归属",
        message = "公共隧道零配置一键使用；自建档案完全可控。",
        options = profiles.map { AppleOption(it.label, selected = it.id == form.profileId) },
        onSelect = { index ->
            profiles.getOrNull(index)?.let { opt ->
                viewModel.update { it.copy(profileId = opt.id) }
            }
        },
    )

    error?.let { msg ->
        AppleAlertDialog(
            visible = true,
            title = "无法保存",
            message = "$msg\n方案：检查填写内容后重试。",
            confirmText = "知道了",
            onConfirm = { viewModel.clearError() },
            dismissText = null,
        )
    }
}
