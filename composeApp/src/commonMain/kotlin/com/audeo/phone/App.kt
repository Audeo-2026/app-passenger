package com.audeo.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.audeo.phone.ui.Audeo
import com.audeo.phone.ui.AudeoTheme
import com.audeo.phone.ui.BannerCard
import com.audeo.phone.ui.MainScreen
import com.audeo.phone.ui.OnboardingScreen
import com.audeo.phone.ui.Space

/** 앱 전체에서 하나만 씁니다(화면이 다시 만들어져도 통화·연결이 이어지도록). */
val appController: AppController by lazy { AppController() }

@Composable
fun App() {
    val s by appController.state.collectAsState()
    AudeoTheme {
        Box(Modifier.fillMaxSize().background(Audeo.colors.ground).windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (s.registered) MainScreen(s, appController) else OnboardingScreen(s, appController)
            s.banner?.let { b ->
                Box(Modifier.align(Alignment.TopCenter).padding(Space.s4)) { BannerCard(b.ok, b.text, appController::dismissBanner) }
            }
        }
    }
}
