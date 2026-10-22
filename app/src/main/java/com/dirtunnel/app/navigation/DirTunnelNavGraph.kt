package com.dirtunnel.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dirtunnel.app.dashboard.DashboardScreen
import com.dirtunnel.app.dashboard.DashboardViewModel
import com.dirtunnel.core.ui.components.AppleIcon
import com.dirtunnel.core.ui.components.LocalMainTabBarSpace
import com.dirtunnel.core.ui.components.appleGlass
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography
import com.dirtunnel.feature.directory.DirectoryListScreen
import com.dirtunnel.feature.directory.DirectoryViewModel
import com.dirtunnel.feature.log.LogScreen
import com.dirtunnel.feature.service.ServiceEditScreen
import com.dirtunnel.feature.service.ServiceListScreen
import com.dirtunnel.feature.setting.SettingScreen
import com.dirtunnel.feature.setting.SettingViewModel
import com.dirtunnel.feature.tunnel.FrpProfileEditScreen
import com.dirtunnel.feature.tunnel.TcpForwardEditScreen
import com.dirtunnel.feature.tunnel.TunnelScreen
import com.dirtunnel.feature.tunnel.TunnelViewModel

/** 路由常量。 */
object Routes {
    const val HOME = "home"
    const val DIRECTORY = "directory"
    const val TUNNEL = "tunnel"
    const val LOG = "log"
    const val SETTING = "setting"

    const val SERVICE_LIST = "service_list/{directoryId}"
    const val SERVICE_EDIT = "service_edit/{serviceId}/{directoryId}"
    const val FRP_PROFILE_EDIT = "frp_profile_edit/{profileId}"
    const val TCP_FORWARD_EDIT = "tcp_forward_edit/{forwardId}"

    fun serviceList(directoryId: Long) = "service_list/$directoryId"
    fun serviceEdit(serviceId: Long, directoryId: Long) = "service_edit/$serviceId/$directoryId"
    fun frpProfileEdit(profileId: Long) = "frp_profile_edit/$profileId"
    fun tcpForwardEdit(forwardId: Long) = "tcp_forward_edit/$forwardId"
}

/** 底部 Tab 定义（悬浮毛玻璃导航方案）。 */
enum class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "首页", Icons.Outlined.Speed),
    DIRECTORY(Routes.DIRECTORY, "目录", Icons.Outlined.Apps),
    TUNNEL(Routes.TUNNEL, "穿透", Icons.Outlined.Cloud),
    LOG(Routes.LOG, "日志", Icons.Outlined.ListAlt),
    SETTING(Routes.SETTING, "设置", Icons.Outlined.Settings),
}

/**
 * 主导航图 + 苹果悬浮毛玻璃底部 TabBar。
 * 五个主 Tab（首页仪表盘 / 目录 / 穿透 / 日志 / 设置）+ 二级页面（服务列表、服务编辑、
 * FRP 档案编辑、TCP 转发编辑）。
 */
@Composable
fun DirTunnelNavGraph() {
    val navController = rememberNavController()
    val colors = appleColors()
    val shellViewModel: ShellViewModel = hiltViewModel()
    val navOpacity by shellViewModel.navBarOpacity.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in MainTab.entries.map { it.route }

    CompositionLocalProvider(
        LocalMainTabBarSpace provides (if (showBottomBar) 78.dp else 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.HOME) {
                    val vm: DashboardViewModel = hiltViewModel()
                    DashboardScreen(
                        onOpenDirectory = { navController.navigate(Routes.DIRECTORY) },
                        onOpenTunnel = { navController.navigate(Routes.TUNNEL) },
                        onOpenLog = { navController.navigate(Routes.LOG) },
                        viewModel = vm,
                    )
                }
                composable(Routes.DIRECTORY) {
                    val vm: DirectoryViewModel = hiltViewModel()
                    DirectoryListScreen(
                        onOpenDirectory = { dirId ->
                            navController.navigate(Routes.serviceList(dirId))
                        },
                        viewModel = vm,
                    )
                }
                composable(Routes.TUNNEL) {
                    val vm: TunnelViewModel = hiltViewModel()
                    TunnelScreen(
                        onBack = { navController.popBackStack() },
                        onAddProfile = { navController.navigate(Routes.frpProfileEdit(0L)) },
                        onEditProfile = { id -> navController.navigate(Routes.frpProfileEdit(id)) },
                        onAddForward = { navController.navigate(Routes.tcpForwardEdit(0L)) },
                        onEditForward = { id -> navController.navigate(Routes.tcpForwardEdit(id)) },
                        viewModel = vm,
                    )
                }
                composable(Routes.LOG) {
                    LogScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.SETTING) {
                    val vm: SettingViewModel = hiltViewModel()
                    SettingScreen(
                        onBack = { navController.popBackStack() },
                        viewModel = vm,
                    )
                }
    
                composable(
                    route = Routes.SERVICE_LIST,
                    arguments = listOf(navArgument("directoryId") { type = NavType.LongType }),
                ) {
                    ServiceListScreen(
                        onBack = { navController.popBackStack() },
                        onOpenService = { serviceId, directoryId ->
                            navController.navigate(Routes.serviceEdit(serviceId, directoryId))
                        },
                    )
                }
                composable(
                    route = Routes.SERVICE_EDIT,
                    arguments = listOf(
                        navArgument("serviceId") { type = NavType.LongType },
                        navArgument("directoryId") { type = NavType.LongType },
                    ),
                ) {
                    ServiceEditScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = Routes.FRP_PROFILE_EDIT,
                    arguments = listOf(navArgument("profileId") { type = NavType.LongType }),
                ) {
                    FrpProfileEditScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = Routes.TCP_FORWARD_EDIT,
                    arguments = listOf(navArgument("forwardId") { type = NavType.LongType }),
                ) {
                    TcpForwardEditScreen(onBack = { navController.popBackStack() })
                }
            }
    
            // 悬浮毛玻璃底栏：圆角 16dp、左右留边、贴底悬浮（与 TECH 四 悬浮导航同方案）
            if (showBottomBar) {
                AppleTabBar(
                    currentRoute = currentRoute,
                    opacity = navOpacity,
                    onSelect = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 10.dp),
                )
            }
        }
    }
}

/** 苹果悬浮毛玻璃底部 TabBar。 */
@Composable
private fun AppleTabBar(
    currentRoute: String?,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    /** 背景不透明度（设置 → 外观 → 导航栏不透明度） */
    opacity: Float = 0.9f,
) {
    val colors = appleColors()
    val typography = appleTypography()
    val hazeState = remember { dev.chrisbanes.haze.HazeState() }

    Row(
        modifier = modifier
            .width(340.dp)
            .height(60.dp)
            .appleGlass(
                hazeState = hazeState,
                colors = colors,
                cornerRadius = 18.dp,
                scrimColor = colors.background,
                scrimOpacity = opacity,
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab ->
            val selected = tab.route == currentRoute
            val interaction = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { onSelect(tab) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppleIcon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) colors.primary else colors.textSecondary,
                    size = 22.dp,
                )
                Spacer(Modifier.height(2.dp))
                AppleTextTabLabel(
                    label = tab.label,
                    selected = selected,
                )
            }
        }
    }
}

@Composable
private fun AppleTextTabLabel(label: String, selected: Boolean) {
    val colors = appleColors()
    val typography = appleTypography()
    androidx.compose.foundation.text.BasicText(
        text = label,
        style = typography.caption2.copy(
            color = if (selected) colors.primary else colors.textSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        ),
    )
}
