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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.storage.dao.FrpProfileDao
import com.dirtunnel.core.storage.data.SecurityStore
import com.dirtunnel.core.storage.entity.FrpProfileEntity
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleButton
import com.dirtunnel.core.ui.components.AppleButtonSize
import com.dirtunnel.core.ui.components.AppleButtonStyle
import com.dirtunnel.core.ui.components.AppleCell
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleSectionHeader
import com.dirtunnel.core.ui.components.AppleSwitchCell
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.AppleTextField
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** FRP 档案表单（REQ 4.3.2：服务器地址、端口、token 图形化配置，无需手写 ini）。 */
data class FrpProfileForm(
    val id: Long = 0L,
    val name: String = "",
    val serverAddr: String = "",
    val serverPort: String = "7000",
    val token: String = "",
    val useTls: Boolean = true,
    val isNew: Boolean = true,
)

@HiltViewModel
class FrpProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val frpProfileDao: FrpProfileDao,
    private val security: SecurityStore,
) : ViewModel() {

    private val profileId: Long =
        // 路由 NavType.LongType 存入的是 Long，按 Any 读取后解析，避免 ClassCastException
        savedStateHandle.get<Any>("profileId")?.toString()?.toLongOrNull() ?: 0L

    private val _form = MutableStateFlow(FrpProfileForm())
    val form: StateFlow<FrpProfileForm> = _form.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            if (profileId != 0L) {
                val entity = frpProfileDao.getById(profileId) ?: return@launch
                _form.value = FrpProfileForm(
                    id = entity.id,
                    name = entity.name,
                    serverAddr = entity.serverAddr,
                    serverPort = entity.serverPort.toString(),
                    token = security.frpToken(entity.id) ?: "",
                    useTls = entity.useTls,
                    isNew = false,
                )
            }
        }
    }

    fun update(transform: (FrpProfileForm) -> FrpProfileForm) {
        _form.value = transform(_form.value)
    }

    fun save() {
        viewModelScope.launch {
            val f = _form.value
            val port = f.serverPort.trim().toIntOrNull()
            if (f.name.isBlank()) {
                _error.value = "请填写档案名称"
                return@launch
            }
            if (f.serverAddr.isBlank()) {
                _error.value = "请填写 frps 服务器地址"
                return@launch
            }
            if (port == null || port !in 1..65535) {
                _error.value = "端口需为 1-65535 的整数"
                return@launch
            }
            if (f.token.isBlank()) {
                _error.value = "请填写 token（与 frps.toml 中 auth.token 一致）"
                return@launch
            }
            val entity = FrpProfileEntity(
                id = if (f.isNew) 0L else f.id,
                name = f.name.trim(),
                serverAddr = f.serverAddr.trim(),
                serverPort = port,
                useTls = f.useTls,
            )
            val id = if (f.isNew) {
                frpProfileDao.insert(entity)
            } else {
                frpProfileDao.update(entity)
                f.id
            }
            security.setFrpToken(id, f.token.trim())
            _saved.value = true
        }
    }

    fun delete() {
        viewModelScope.launch {
            frpProfileDao.getById(profileId)?.let { frpProfileDao.delete(it) }
            security.setFrpToken(profileId, null)
            _deleted.value = true
        }
    }

    fun consumeSaved() { _saved.value = false }
    fun consumeDeleted() { _deleted.value = false }
    fun clearError() { _error.value = null }
}

/**
 * FRP 档案编辑（REQ 4.3.2：图形化配置 frps 服务器地址、端口、token，无需手写 ini）。
 * token 加密存储（TECH 六.1），界面脱敏显示。
 */
@Composable
fun FrpProfileEditScreen(
    onBack: () -> Unit,
    viewModel: FrpProfileViewModel = hiltViewModel(),
) {
    val hazeState = rememberHazeState()
    val colors = appleColors()
    val typography = appleTypography()

    val form by viewModel.form.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

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
        title = if (form.isNew) "添加 FRP 档案" else "编辑 FRP 档案",
        subtitle = "自建 frps 服务端 · 图形化配置",
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
                    "连接信息",
                    footer = "对应 frps.toml 的 bindAddr / bindPort / auth.token，DirTunnel 自动生成 frpc 配置。",
                )
                AppleGroupCard {
                    AppleCell(title = "档案名称", showSeparator = true) {
                        AppleTextField(
                            value = form.name,
                            onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                            placeholder = "如：我的云服务器",
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(title = "服务器地址", showSeparator = true) {
                        AppleTextField(
                            value = form.serverAddr,
                            onValueChange = { v -> viewModel.update { it.copy(serverAddr = v) } },
                            placeholder = "frps.example.com 或 IP",
                            showBorder = false,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(title = "服务端口", showSeparator = true) {
                        AppleTextField(
                            value = form.serverPort,
                            onValueChange = { v -> viewModel.update { it.copy(serverPort = v.filter { c -> c.isDigit() }) } },
                            placeholder = "7000",
                            showBorder = false,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(
                        title = "Token",
                        subtitle = "加密存储，不进命令行",
                        showSeparator = false,
                    ) {
                        AppleTextField(
                            value = form.token,
                            onValueChange = { v -> viewModel.update { it.copy(token = v) } },
                            placeholder = "frps.toml 中的 auth.token",
                            isPassword = true,
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                AppleSectionHeader("安全")
                AppleGroupCard {
                    AppleSwitchCell(
                        title = "启用 TLS 传输加密",
                        subtitle = "frpc ↔ frps 之间走 TLS，推荐开启",
                        checked = form.useTls,
                        onCheckedChange = { v -> viewModel.update { it.copy(useTls = v) } },
                        showSeparator = false,
                    )
                }
            }

            if (!form.isNew) {
                item {
                    AppleSectionHeader("危险操作")
                    AppleGroupCard {
                        AppleCell(
                            title = "删除该档案",
                            subtitle = "同时清除加密存储中的 token",
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
