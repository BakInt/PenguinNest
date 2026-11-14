package org.melodist.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「上次启动崩了」全屏报告页。
 *
 * 为什么需要它：release 包被 R8 剥掉了 `printStackTrace` 与 `Log.d/i/v`，真机上「点图标就闪退」
 * 时既没有 logcat 也没有可读日志，用户进不去设置页的「诊断日志」。这个页面在**应用主界面组合之前**
 * 就把上次的崩溃栈摆出来（数据来自 [org.melodist.data.CrashGuard.startupCrashReport]），
 * 用户直接截图或长按复制即可回传。
 *
 * 刻意不依赖 [org.melodist.mobile.ui.theme.QQMusicMobileTheme]：崩因可能就是设置项/主题解析，
 * 这里只用 Material3 默认主题，保证一定能渲染出来。
 */
@Composable
fun StartupCrashScreen(
    text: String,
    onContinue: () -> Unit,
    onClearAndContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Text(
                text = "上次启动崩溃了",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "下面是崩溃现场（可长按复制或直接截图）。如果每次启动都停在这里，请把这段内容发回给开发者。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onClearAndContinue,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空日志并继续")
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("继续启动")
                }
            }
        }
    }
}
