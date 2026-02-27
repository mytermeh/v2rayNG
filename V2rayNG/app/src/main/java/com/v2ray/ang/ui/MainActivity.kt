package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.databinding.DialogImportSubscriptionBinding
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.enums.RoutingType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private var isQuickTestRunning = false
    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestVpnForTest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            proceedQuickTest()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }

        setupRoutingModeToggle()
        setupQuickTestButton()
        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.quickTestFinished.observe(this) { finished ->
            if (finished == true) {
                val wasQuickTest = mainViewModel.isQuickTest
                isQuickTestRunning = false
                mainViewModel.isQuickTest = false
                binding.btnQuickTest.text = getString(R.string.quick_test_button)
                binding.btnQuickTest.isEnabled = true
                setTestState(getString(R.string.quick_test_done))
                mainViewModel.quickTestFinished.value = false

                // Auto-connect to the best server after Quick Test
                if (wasQuickTest) {
                    if (mainViewModel.isRunning.value == true) {
                        restartV2Ray()
                    } else {
                        startV2Ray()
                    }
                }
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        // Long-press to rename subscription tabs
        for (i in 0 until binding.tabGroup.tabCount) {
            val tab = binding.tabGroup.getTabAt(i) ?: continue
            val tabId = tab.tag as? String ?: continue
            if (tabId.isEmpty() || tabId == AppConfig.DEFAULT_SUBSCRIPTION_ID) continue
            tab.view.setOnLongClickListener {
                showRenameSubscriptionDialog(tabId, tab.text?.toString().orEmpty())
                true
            }
        }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        // Show tabs unless the only group is Default
        val onlyDefault = groups.size == 1 && groups[0].id == AppConfig.DEFAULT_SUBSCRIPTION_ID
        binding.tabGroup.isVisible = !onlyDefault
    }

    private fun showRenameSubscriptionDialog(subscriptionId: String, currentName: String) {
        val editText = EditText(this).apply {
            setText(currentName)
            selectAll()
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            setPadding(dp16, dp16, dp16, 0)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_rename_subscription)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentName) {
                    val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return@setPositiveButton
                    subItem.remarks = newName
                    MmkvManager.encodeSubscription(subscriptionId, subItem)
                    setupGroupTab()
                    toast(R.string.toast_rename_success)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handleFabAction() {
        if (mainViewModel.isRunning.value == true) {
            // Immediate UI feedback for stop
            applyRunningState(isLoading = false, isRunning = false)
            V2RayServiceManager.stopVService(this)
        } else {
            // Show connecting state immediately
            applyRunningState(isLoading = true, isRunning = false)
            if (SettingsManager.isVpnMode()) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private  fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    private fun setupRoutingModeToggle() {
        // Restore saved mode
        val savedMode = MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_MODE)

        // First launch: apply Rule as default
        if (savedMode == null) {
            applyRoutingMode("rule", RoutingType.WHITE_IRAN.ordinal, showToast = false)
            binding.toggleRoutingMode.check(R.id.btn_mode_rule)
        } else {
            val checkedId = when (savedMode) {
                "global" -> R.id.btn_mode_global
                "direct" -> R.id.btn_mode_direct
                else -> R.id.btn_mode_rule
            }
            binding.toggleRoutingMode.check(checkedId)
        }

        binding.toggleRoutingMode.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (buttonId) {
                R.id.btn_mode_rule -> applyRoutingMode("rule", RoutingType.WHITE_IRAN.ordinal)
                R.id.btn_mode_global -> applyRoutingMode("global", RoutingType.GLOBAL.ordinal)
                R.id.btn_mode_direct -> applyRoutingMode("direct", RoutingType.DIRECT.ordinal)
            }
        }
    }

    private fun applyRoutingMode(modeName: String, routingIndex: Int, showToast: Boolean = true) {
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_MODE, modeName)

        lifecycleScope.launch(Dispatchers.IO) {
            SettingsManager.resetRoutingRulesetsFromPresets(this@MainActivity, routingIndex)
            withContext(Dispatchers.Main) {
                if (showToast) {
                    val label = when (modeName) {
                        "rule" -> getString(R.string.routing_mode_rule)
                        "global" -> getString(R.string.routing_mode_global)
                        "direct" -> getString(R.string.routing_mode_direct)
                        else -> modeName
                    }
                    toast(getString(R.string.routing_mode_applied, label))
                }

                // Restart VPN if running
                if (mainViewModel.isRunning.value == true) {
                    restartV2Ray()
                }
            }
        }
    }

    private fun setupQuickTestButton() {
        binding.btnQuickTest.setOnClickListener {
            if (isQuickTestRunning) {
                cancelQuickTest()
            } else {
                startQuickTest()
            }
        }
    }

    private fun startQuickTest() {
        if (mainViewModel.serversCache.isEmpty()) {
            toast(R.string.quick_test_no_servers)
            return
        }
        // Request VPN permission upfront so auto-connect works after test
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                requestVpnForTest.launch(intent)
                return
            }
        }
        proceedQuickTest()
    }

    private fun proceedQuickTest() {
        isQuickTestRunning = true
        binding.btnQuickTest.text = getString(R.string.quick_test_cancel)
        mainViewModel.forceAutoSort = true
        mainViewModel.isQuickTest = true
        mainViewModel.testAllRealPing()
    }

    private fun cancelQuickTest() {
        isQuickTestRunning = false
        mainViewModel.isQuickTest = false
        binding.btnQuickTest.text = getString(R.string.quick_test_button)
        MessageUtil.sendMsg2TestService(this, AppConfig.MSG_MEASURE_CONFIG_CANCEL, "")
    }

    override fun onResume() {
        super.onResume()
        checkForAppUpdate()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.add_config -> {
            val anchor = binding.toolbar.findViewById<View>(R.id.add_config) ?: binding.toolbar
            showAddConfigSheet(anchor)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }


        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            if (clipboard.isNullOrBlank()) {
                toastError(R.string.toast_failure)
                return false
            }

            // Smart detection: check if clipboard contains a subscription URL
            val subUrls = AngConfigManager.extractSubscriptionUrls(clipboard)
            if (subUrls.isNotEmpty()) {
                showImportSubscriptionDialog(subUrls.first())
            } else {
                importBatchConfig(clipboard)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun showImportSubscriptionDialog(url: String) {
        // Check for duplicate
        val existingGuid = AngConfigManager.findExistingSubscriptionByUrl(url)
        if (existingGuid != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.title_sub_import)
                .setMessage(R.string.toast_sub_already_exists)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val dialogBinding = DialogImportSubscriptionBinding.inflate(layoutInflater)

        val suggestedName = AngConfigManager.extractSubscriptionName(url)
        dialogBinding.etSubName.setText(suggestedName)
        dialogBinding.tvSubUrl.text = url
        dialogBinding.chkEnable.isChecked = true
        dialogBinding.chkAutoUpdate.isChecked = true

        AlertDialog.Builder(this)
            .setTitle(R.string.title_sub_import)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.menu_item_save_config) { _, _ ->
                val name = dialogBinding.etSubName.text.toString().ifBlank { suggestedName }
                val enabled = dialogBinding.chkEnable.isChecked
                val autoUpdate = dialogBinding.chkAutoUpdate.isChecked

                val subItem = SubscriptionItem(
                    remarks = name,
                    url = url,
                    enabled = enabled,
                    autoUpdate = autoUpdate
                )

                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val guid = AngConfigManager.importSubscription(subItem)
                    val count = AngConfigManager.updateConfigViaSingleSub(guid)
                    delay(500L)
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        if (count > 0) {
                            toast(getString(R.string.title_import_sub_config_count, name, count))
                        } else {
                            toast(R.string.import_subscription_success)
                        }
                        mainViewModel.subscriptionIdChanged(guid)
                        setupGroupTab()
                        mainViewModel.reloadServerList()

                        // Show rename hint after a short delay
                        lifecycleScope.launch {
                            delay(1500L)
                            toast(R.string.hint_long_press_rename)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(getString(R.string.title_update_config_count, count))
                    mainViewModel.reloadServerList()
                } else {
                    toastError(R.string.toast_failure)
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.mux_fragment_benchmark -> startActivity(Intent(this, MuxFragmentBenchmarkActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    /**
     * Checks for app updates on every launch and shows banner if update is available.
     */
    private fun checkForAppUpdate() {
        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(false)
                withContext(Dispatchers.Main) {
                    showUpdateBanner(result.latestVersion.takeIf { result.hasUpdate })
                }
            } catch (_: Exception) {
                // Silent failure
            }
        }
    }

    private fun showUpdateBanner(latestVersion: String?) {
        if (latestVersion != null) {
            binding.layoutUpdateBanner.isVisible = true
            binding.tvUpdateBannerText.text = getString(R.string.update_banner_text, latestVersion)
            binding.layoutUpdateBanner.setOnClickListener {
                startActivity(Intent(this, CheckUpdateActivity::class.java))
            }
        } else {
            binding.layoutUpdateBanner.isVisible = false
        }
    }

    private fun showAddConfigSheet(anchor: View) {
        val ctx = ContextThemeWrapper(this, R.style.CompactPopupMenuTheme)
        val popup = PopupMenu(ctx, anchor)
        popup.menuInflater.inflate(R.menu.menu_add_config, popup.menu)
        try {
            val f = popup.javaClass.getDeclaredField("mPopup")
            f.isAccessible = true
            val p = f.get(popup)
            p.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(p, true)
        } catch (_: Exception) {}

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.import_clipboard -> importClipboard()
                R.id.import_qrcode -> importQRcode()
                R.id.import_local -> importConfigLocal()
                R.id.add_manually -> {
                    showAddManuallyMenu(anchor)
                    true
                }
                else -> false
            }
            true
        }
        popup.show()
    }

    private fun showAddManuallyMenu(anchor: View) {
        val ctx = ContextThemeWrapper(this, R.style.CompactPopupMenuTheme)
        val popup = PopupMenu(ctx, anchor)
        popup.menuInflater.inflate(R.menu.menu_add_manually, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.import_manually_vmess -> importManually(EConfigType.VMESS.value)
                R.id.import_manually_vless -> importManually(EConfigType.VLESS.value)
                R.id.import_manually_ss -> importManually(EConfigType.SHADOWSOCKS.value)
                R.id.import_manually_trojan -> importManually(EConfigType.TROJAN.value)
                R.id.import_manually_hysteria2 -> importManually(EConfigType.HYSTERIA2.value)
                R.id.import_manually_wireguard -> importManually(EConfigType.WIREGUARD.value)
                R.id.import_manually_socks -> importManually(EConfigType.SOCKS.value)
                R.id.import_manually_http -> importManually(EConfigType.HTTP.value)
                R.id.import_manually_policy_group -> importManually(EConfigType.POLICYGROUP.value)
            }
            true
        }
        popup.show()
    }

    private fun stripMarkdown(text: String): String {
        return text.lines().joinToString("\n") { line ->
            line.replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
                .let { if (it.trim() == "---") "" else it }
        }.trim()
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
}