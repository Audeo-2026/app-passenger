package com.audeo.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.any { it.value }) appController.permissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidPlatform.init(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
        // 통화 음성에는 마이크, 차 찾기에는 정밀 위치가 필요해요(dangerous 권한이라 실행 중에 허락을 받습니다).
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && savedInstanceState == null) permissions.launch(missing.toTypedArray())
    }
}
