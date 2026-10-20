package com.hape.pdfscan.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hape.pdfscan.ui.about.AboutScreen
import com.hape.pdfscan.ui.main.MainMenuScreen
import com.hape.pdfscan.ui.scan.ScanScreen
import com.hape.pdfscan.ui.settings.SettingsScreen
import com.hape.pdfscan.ui.viewer.PdfViewerScreen
import java.io.File

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.MAIN,
        modifier = modifier,
    ) {
        composable(NavRoutes.MAIN) {
            MainMenuScreen(
                onNavigateToScan = { targetDirectory: File? ->
                    navController.navigate(NavRoutes.scanRoute(targetDirectory))
                },
                onNavigateToAbout = {
                    navController.navigate(NavRoutes.ABOUT)
                },
                onNavigateToSettings = {
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onNavigateToPdfViewer = { pdfFile: File ->
                    navController.navigate(NavRoutes.pdfViewerRoute(pdfFile))
                },
            )
        }
        composable(
            route = NavRoutes.SCAN,
            arguments = listOf(
                navArgument("targetDirectory") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val encodedTarget = backStackEntry.arguments?.getString("targetDirectory")
            val targetDirectory = NavRoutes.parseTargetDirectory(encodedTarget)
            ScanScreen(
                targetDirectory = targetDirectory,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(NavRoutes.ABOUT) {
            AboutScreen(
                onClose = { navController.popBackStack() },
            )
        }
        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(
            route = NavRoutes.PDF_VIEWER,
            arguments = listOf(
                navArgument("path") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val pdfFile = NavRoutes.parsePdfFile(backStackEntry.arguments?.getString("path"))
            if (pdfFile != null) {
                PdfViewerScreen(
                    pdfFile = pdfFile,
                    onNavigateBack = { navController.popBackStack() },
                )
            } else {
                navController.popBackStack()
            }
        }
    }
}
