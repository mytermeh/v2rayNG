package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityCheckUpdateBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.V2RayNativeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }
    private var currentResult: CheckUpdateResult? = null
    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))

        binding.layoutCheckUpdate.setOnClickListener {
            if (!isDownloading) {
                checkForUpdates(binding.checkPreRelease.isChecked)
            }
        }

        binding.checkPreRelease.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, isChecked)
        }
        binding.checkPreRelease.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)

        "v${BuildConfig.VERSION_NAME} (${V2RayNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        binding.btnDownload.setOnClickListener { startDownloadAndInstall() }
        binding.btnLater.setOnClickListener { finish() }

        checkForUpdates(binding.checkPreRelease.isChecked)
    }

    private fun checkForUpdates(includePreRelease: Boolean) {
        toast(R.string.update_checking_for_update)
        showLoading()
        binding.cardUpdateInfo.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(includePreRelease)
                UpdateCheckerManager.markUpdateChecked()

                if (result.hasUpdate) {
                    currentResult = result
                    showUpdateCard(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            } finally {
                hideLoading()
            }
        }
    }

    private fun showUpdateCard(result: CheckUpdateResult) {
        binding.cardUpdateInfo.visibility = View.VISIBLE
        binding.tvUpdateVersion.text = getString(R.string.update_new_version_found, result.latestVersion)

        val notes = result.releaseNotes?.trim()?.let { stripMarkdown(it) }
        if (!notes.isNullOrEmpty()) {
            binding.tvReleaseNotesLabel.visibility = View.VISIBLE
            binding.tvReleaseNotes.visibility = View.VISIBLE
            binding.tvReleaseNotes.text = notes
        } else {
            binding.tvReleaseNotesLabel.visibility = View.GONE
            binding.tvReleaseNotes.visibility = View.GONE
        }

        // Reset download UI
        binding.progressDownload.visibility = View.GONE
        binding.tvDownloadStatus.visibility = View.GONE
        binding.layoutButtons.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = true
        binding.btnDownload.text = getString(R.string.update_download_and_install)
    }

    private fun startDownloadAndInstall() {
        val result = currentResult ?: return
        val downloadUrl = result.downloadUrl ?: return

        // Check install permission on Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast(R.string.update_install_permission_required)
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        isDownloading = true
        binding.btnDownload.isEnabled = false
        binding.btnDownload.text = getString(R.string.update_downloading)
        binding.progressDownload.visibility = View.VISIBLE
        binding.progressDownload.progress = 0
        binding.tvDownloadStatus.visibility = View.VISIBLE
        binding.tvDownloadStatus.text = getString(R.string.update_download_progress, 0)

        lifecycleScope.launch {
            try {
                val apkFile = UpdateCheckerManager.downloadApk(this@CheckUpdateActivity, downloadUrl) { percent, _, _ ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.progressDownload.progress = percent
                        binding.tvDownloadStatus.text = getString(R.string.update_download_progress, percent)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (apkFile != null && apkFile.exists()) {
                        binding.tvDownloadStatus.text = getString(R.string.update_download_complete)
                        binding.btnDownload.text = getString(R.string.update_installing)

                        UpdateCheckerManager.installApk(this@CheckUpdateActivity, apkFile)
                    } else {
                        toastError(R.string.update_download_failed)
                        binding.btnDownload.isEnabled = true
                        binding.btnDownload.text = getString(R.string.update_download_and_install)
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Download failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    toastError(R.string.update_download_failed)
                    binding.btnDownload.isEnabled = true
                    binding.btnDownload.text = getString(R.string.update_download_and_install)
                }
            } finally {
                isDownloading = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-enable download button if user comes back from install permission screen
        if (!isDownloading && currentResult != null) {
            binding.btnDownload.isEnabled = true
            binding.btnDownload.text = getString(R.string.update_download_and_install)
        }
    }

    private fun stripMarkdown(text: String): String {
        return text.lines().joinToString("\n") { line ->
            line.replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
                .let { if (it.trim() == "---") "" else it }
        }.trim()
    }
}
