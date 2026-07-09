package com.benzn.grandtime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Text("GrandTime") }
        }
        // 占位启动(Task 9 验证用);Task 11 重写整个文件时替换为正式入口
        startForegroundService(android.content.Intent(this, com.benzn.grandtime.service.CoreService::class.java))
    }
}
