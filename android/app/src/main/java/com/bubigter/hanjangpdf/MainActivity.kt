package com.bubigter.hanjangpdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

// 8-1b(호환 실증) 동안의 자리표시 화면. 실제 화면은 8-2부터.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("한장PDF") }
    }
}
