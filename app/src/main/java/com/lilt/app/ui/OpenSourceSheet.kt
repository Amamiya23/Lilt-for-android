@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lilt.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class OpenSourceProject(
    val name: String,
    val license: String,
    val repository: String,
    val licensePath: String,
    val localLicense: String? = null,
)

// Reference projects are credited alongside the libraries used by the Android app.
private val OpenSourceProjects = listOf(
    OpenSourceProject("mygo-qyin", "AGPL-3.0", "https://github.com/Amamiya23/mygo-qyin", "blob/main/LICENSE"),
    OpenSourceProject("qq-music-api", "MIT", "https://github.com/Rain120/qq-music-api", "blob/next/LICENSE"),
    OpenSourceProject("Tabler Icons", "MIT", "https://github.com/tabler/tabler-icons", "blob/main/LICENSE", "tabler-icons.txt"),
    OpenSourceProject("Go · crypto/des", "BSD-3-Clause", "https://github.com/golang/go", "blob/master/LICENSE", "go-des.txt"),
    OpenSourceProject("AndroidX · Jetpack Compose", "Apache-2.0", "https://github.com/androidx/androidx", "blob/androidx-main/LICENSE.txt"),
    OpenSourceProject("Media3", "Apache-2.0", "https://github.com/androidx/media", "blob/release/LICENSE"),
    OpenSourceProject("Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin", "blob/master/license/LICENSE.txt"),
    OpenSourceProject("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines", "blob/master/LICENSE.txt"),
    OpenSourceProject("Coil", "Apache-2.0", "https://github.com/coil-kt/coil", "blob/2.7.0/LICENSE.txt"),
    OpenSourceProject("OkHttp", "Apache-2.0", "https://github.com/square/okhttp", "blob/parent-4.12.0/LICENSE.txt"),
)

@Composable
fun OpenSourceSheet(onDismiss: () -> Unit, onOpenError: () -> Unit) {
    val context = LocalContext.current
    var localText by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { onOpenError() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("开源许可", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconAction(Glyph.Close, "关闭开源许可", onClick = onDismiss)
                }
            }
            itemsIndexed(OpenSourceProjects, key = { _, project -> project.name }) { index, project ->
                if (index == 0 || index == 2) Text(if (index == 0) "参考项目" else "依赖与组件",
                    Modifier.padding(top = if (index == 0) 0.dp else 20.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp)
                    .quietClickable(role = Role.Button, onClickLabel = "查看${project.name}许可证") {
                        if (project.localLicense != null) {
                            runCatching { context.assets.open("licenses/${project.localLicense}").bufferedReader().use { it.readText() } }
                                .onSuccess { localText = project.name to it }.onFailure { onOpenError() }
                        } else openUrl("${project.repository}/${project.licensePath}")
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(project.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(project.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconAction(Glyph.External, "查看${project.name}项目", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconSize = 20.dp, onClick = { openUrl(project.repository) })
                }
                if (index != OpenSourceProjects.lastIndex && index != 1) RowDivider()
            }
        }
    }
    localText?.let { (name, license) ->
        AlertDialog(onDismissRequest = { localText = null }, title = { Text(name) },
            text = { Text(license, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { QuietTextButton(onClick = { localText = null }) { Text("关闭") } })
    }
}
