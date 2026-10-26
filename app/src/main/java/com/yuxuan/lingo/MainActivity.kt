package com.yuxuan.lingo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuxuan.lingo.ui.LingoApp
import com.yuxuan.lingo.feedback.InteractionFeedback

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var feedback: InteractionFeedback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        feedback = InteractionFeedback(this, window.decorView)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            // Count startup as ready only once a word (or completed session) is
            // usable, rather than when the initial loading surface is painted.
            ReportDrawnWhen { !state.isLoading && state.errorMessage == null }
            LingoApp(state) { action ->
                if (viewModel.dispatch(action)) feedback.play(action, viewModel.state.value.feedback)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        feedback.setForeground(true)
        viewModel.setStudyForeground(true)
        viewModel.refreshStats()
    }

    override fun onPause() {
        viewModel.setStudyForeground(false)
        feedback.setForeground(false)
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        viewModel.userInteracted()
    }

    override fun onDestroy() {
        feedback.close()
        super.onDestroy()
    }

}
