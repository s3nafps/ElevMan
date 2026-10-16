package com.elevman.ui.backup

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elevman.AppContainer
import java.io.File

/**
 * The Backup tab: [BackupScreen] on top of [BackupViewModel], the file picker for an import, and
 * the share sheet for an export.
 */
@Composable
fun BackupRoute(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: BackupViewModel = viewModel(
        factory = BackupViewModel.factory(
            writer = container.backupWriter,
            reader = container.backupReader,
            applier = container.backupApplier,
            files = container.backupFiles,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The data can change while another tab is open, so the size is worked out again on every visit.
    LaunchedEffect(viewModel) { viewModel.refreshSize() }

    // Any file can be picked: a backup that came through a messenger may carry any type. The reader
    // rejects what is not a backup, with a message.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBackup(uri)
    }

    val shareFile = state.shareFile
    LaunchedEffect(shareFile) {
        if (shareFile != null) {
            if (openShareSheet(context, shareFile)) viewModel.shareHandled() else viewModel.shareFailed()
        }
    }

    BackupScreen(
        state = state,
        onShare = viewModel::share,
        onImport = { picker.launch(arrayOf("*/*")) },
        onApply = viewModel::applyImport,
        onCancel = viewModel::cancelImport,
        onDismiss = viewModel::dismissNotice,
        modifier = modifier,
    )
}

/** The authority declared for the app's `FileProvider` in the manifest: `${applicationId}.fileprovider`. */
private fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"

/** Sends the zip to another app. The receiver is given read access to this one file only. */
internal fun shareBackupIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "application/zip"
    putExtra(Intent.EXTRA_STREAM, uri)
    clipData = ClipData.newRawUri(null, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Opens the share sheet for [file]. Returns false when it cannot be opened. */
private fun openShareSheet(context: Context, file: File): Boolean = try {
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    context.startActivity(Intent.createChooser(shareBackupIntent(uri), "Share backup"))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: IllegalArgumentException) {
    // The file is outside the folders file_paths.xml shares.
    false
}
