package com.smartpay.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentTab = "DASHBOARD"
    private var selectedTxFilter = "ALL"
    private var searchQuery = ""

    private lateinit var rootFrame: FrameLayout

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AppStorage.isLoggedIn(this@MainActivity)) {
                renderLoggedInScreen()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B1120"))
        }
        setContentView(rootFrame)

        checkAndRequestPermissions()
        promptBatteryOptimizationIfNeeded()

        if (AppStorage.isLoggedIn(this)) {
            SyncForegroundService.start(this, retryNow = true)
            renderLoggedInScreen()
        } else {
            renderLoginScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SmsReceiver.ACTION_UI_REFRESH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
        if (AppStorage.isLoggedIn(this)) {
            renderLoggedInScreen()
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    private fun boxBg(colorHex: String, borderHex: String? = null, radius: Float = 28f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = radius
            if (borderHex != null) {
                setStroke(2, Color.parseColor(borderHex))
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderLoginScreen() {
        rootFrame.removeAllViews()

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 80, 48, 80)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg("#1E293B", "#334155", 36f)
            setPadding(44, 54, 44, 54)
        }

        val logoCircle = TextView(this).apply {
            text = "⚡"
            textSize = 36f
            gravity = Gravity.CENTER
            background = boxBg("#0284C7", null, 100f)
            layoutParams = LinearLayout.LayoutParams(150, 150).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 28
            }
        }

        val title = TextView(this).apply {
            text = "SmartPay Sync Login"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Enter your API Endpoint URL and Device Key."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 40)
        }

        val urlLabel = TextView(this).apply {
            text = "API Endpoint URL"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }

        val urlInput = EditText(this).apply {
            hint = "https://yourdomain.com/api/add-data"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            background = boxBg("#0F172A", "#334155", 20f)
            setPadding(30, 26, 30, 26)
            setText(AppStorage.getEndpoint(this@MainActivity))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12; bottomMargin = 28 }
        }

        val keyLabel = TextView(this).apply {
            text = "Device Key"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }

        val keyInput = EditText(this).apply {
            hint = "Enter Device Key"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            background = boxBg("#0F172A", "#334155", 20f)
            setPadding(30, 26, 30, 26)
            setText(AppStorage.getDeviceKey(this@MainActivity))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12; bottomMargin = 24 }
        }

        val agreeCheck = CheckBox(this).apply {
            text = "I agree to monitor & sync official bKash payment SMS."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            isChecked = true
        }

        val connectBtn = Button(this).apply {
            text = "Connect Now"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            background = boxBg("#0284C7", null, 24f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 }
        }

        connectBtn.setOnClickListener {
            val ep = urlInput.text.toString().trim()
            val dk = keyInput.text.toString().trim()
            if (ep.isBlank() || !ep.startsWith("http")) {
                Toast.makeText(this, "Enter valid Endpoint URL", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (dk.length < 16) {
                Toast.makeText(this, "Device Key must be at least 16 characters", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!agreeCheck.isChecked) {
                Toast.makeText(this, "Please check agreement box", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            connectBtn.isEnabled = false
            connectBtn.text = "Connecting..."

            uiScope.launch {
                val res = withContext(Dispatchers.IO) {
                    ApiClient.pingServer(ep, dk)
                }
                connectBtn.isEnabled = true
                connectBtn.text = "Connect Now"

                if (res.success || res.httpCode in 200..299) {
                    AppStorage.saveLoginConfig(this@MainActivity, ep, dk)
                    SyncForegroundService.start(this@MainActivity, retryNow = true)
                    Toast.makeText(this@MainActivity, "Device Connected Successfully!", Toast.LENGTH_SHORT).show()
                    renderLoggedInScreen()
                } else {
                    Toast.makeText(this@MainActivity, "Connection Failed: ${res.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        card.addView(logoCircle)
        card.addView(title)
        card.addView(subtitle)
        card.addView(urlLabel)
        card.addView(urlInput)
        card.addView(keyLabel)
        card.addView(keyInput)
        card.addView(agreeCheck)
        card.addView(connectBtn)

        container.addView(card)
        scroll.addView(container)
        rootFrame.addView(scroll)
    }

    @SuppressLint("SetTextI18n")
    private fun renderLoggedInScreen() {
        rootFrame.removeAllViews()

        val mainVertical = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(36, 36, 36, 36)
        }

        val topTitle = TextView(this).apply {
            text = when (currentTab) {
                "DASHBOARD" -> "⚡ Dashboard"
                "TRANSACTIONS" -> "📩 Transactions SMS"
                "MANUAL" -> "✍️ Add Data Manually"
                else -> "⚙️ Settings & Guide"
            }
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val logoutBtn = TextView(this).apply {
            text = "🚪 Logout"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = boxBg("#0F172A", "#7F1D1D", 18f)
            setPadding(24, 14, 24, 14)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Logout Account")
                    .setMessage("Are you sure you want to logout?")
                    .setPositiveButton("Logout") { _, _ ->
                        AppStorage.logout(this@MainActivity)
                        SyncForegroundService.stop(this@MainActivity)
                        renderLoginScreen()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        topBar.addView(topTitle)
        topBar.addView(logoutBtn)
        mainVertical.addView(topBar)

        val contentScroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 32, 36, 48)
        }

        when (currentTab) {
            "DASHBOARD" -> buildDashboardView(contentContainer)
            "TRANSACTIONS" -> buildTransactionsView(contentContainer)
            "MANUAL" -> buildManualEntryView(contentContainer)
            "SETTINGS" -> buildSettingsView(contentContainer)
        }

        contentScroll.addView(contentContainer)
        mainVertical.addView(contentScroll)

        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(12, 16, 12, 16)
        }

        val tabs = listOf(
            "DASHBOARD" to "🏠 Dashboard",
            "TRANSACTIONS" to "📄 SMS List",
            "MANUAL" to "📋 Manual",
            "SETTINGS" to "⚙️ Settings"
        )

        for ((tabKey, tabLabel) in tabs) {
            val active = (currentTab == tabKey)
            val tabBtn = TextView(this).apply {
                text = tabLabel
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
                background = if (active) boxBg("#0F172A", "#0284C7", 20f) else null
                setPadding(12, 20, 12, 20)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 6; marginEnd = 6
                }
                setOnClickListener {
                    currentTab = tabKey
                    renderLoggedInScreen()
                }
            }
            bottomNav.addView(tabBtn)
        }

        mainVertical.addView(bottomNav)
        rootFrame.addView(mainVertical)
    }

    @SuppressLint("SetTextI18n")
    private fun buildDashboardView(parent: LinearLayout) {
        val records = AppStorage.getRecords(this)
        val totalCount = records.size
        val successCount = records.count { it.status == "Success" || it.status == "Used" }
        val pendingCount = records.count { it.status == "Pending" }
        val failedCount = records.count { it.status == "Failed" || it.status == "Flagged" }

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg("#064E3B", "#10B981", 28f)
            setPadding(36, 32, 36, 32)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 28 }
        }
        statusCard.addView(TextView(this).apply {
            text = "🟢 Service running. Screen off background monitor active."
            setTextColor(Color.parseColor("#A7F3D0"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        statusCard.addView(TextView(this).apply {
            text = "Endpoint: ${AppStorage.getEndpoint(this@MainActivity)}"
            setTextColor(Color.parseColor("#D1FAE5"))
            textSize = 11f
            setPadding(0, 8, 0, 0)
        })
        parent.addView(statusCard)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 }
        }
        row1.addView(makeStatCard("Total SMS", totalCount.toString(), "#38BDF8", true))
        row1.addView(makeStatCard("Success / Used", successCount.toString(), "#22C55E", false))
        parent.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 28 }
        }
        row2.addView(makeStatCard("Pending Queue", pendingCount.toString(), "#FACC15", true))
        row2.addView(makeStatCard("Failed / Flagged", failedCount.toString(), "#F87171", false))
        parent.addView(row2)

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 32 }
        }
        val retryAllBtn = Button(this).apply {
            text = "🚀 Retry Pending ($pendingCount)"
            setTextColor(Color.WHITE)
            background = boxBg("#0284C7", null, 20f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 }
            setOnClickListener {
                SyncForegroundService.start(this@MainActivity, retryNow = true)
                Toast.makeText(this@MainActivity, "Retrying pending queue...", Toast.LENGTH_SHORT).show()
            }
        }
        val addManualBtn = Button(this).apply {
            text = "📋 Paste SMS"
            setTextColor(Color.WHITE)
            background = boxBg("#7C3AED", null, 20f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 10 }
            setOnClickListener {
                currentTab = "MANUAL"
                renderLoggedInScreen()
            }
        }
        quickRow.addView(retryAllBtn)
        quickRow.addView(addManualBtn)
        parent.addView(quickRow)

        parent.addView(TextView(this).apply {
            text = "Recent Transactions SMS"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        if (records.isEmpty()) {
            parent.addView(TextView(this).apply {
                text = "No transactions SMS found"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 14f
                setPadding(0, 24, 0, 24)
            })
        } else {
            records.take(10).forEach { item ->
                parent.addView(buildSmsItemCard(item))
            }
        }
    }

    private fun makeStatCard(label: String, value: String, colorHex: String, isLeft: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg("#1E293B", "#334155", 24f)
            setPadding(32, 28, 32, 28)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (isLeft) rightMargin = 10 else leftMargin = 10
            }
            addView(TextView(this@MainActivity).apply {
                text = value
                setTextColor(Color.parseColor(colorHex))
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 6, 0, 0)
            })
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildTransactionsView(parent: LinearLayout) {
        val searchInput = EditText(this).apply {
            hint = "Search sender, amount, trx id..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = boxBg("#1E293B", "#334155", 20f)
            setPadding(30, 22, 30, 22)
            setText(searchQuery)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 }
        }

        val listWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateFilteredList() {
            listWrapper.removeAllViews()
            val all = AppStorage.getRecords(this)
            val filtered = all.filter { item ->
                val matchesTab = when (selectedTxFilter) {
                    "Pending" -> item.status == "Pending"
                    "Success" -> item.status == "Success" || item.status == "Used"
                    "Failed" -> item.status == "Failed" || item.status == "Flagged"
                    else -> true
                }
                val q = searchQuery.trim().lowercase()
                val matchesQuery = q.isEmpty() ||
                    item.trxId.lowercase().contains(q) ||
                    item.senderNumber.lowercase().contains(q) ||
                    item.amountBdt.toString().contains(q) ||
                    item.message.lowercase().contains(q)
                matchesTab && matchesQuery
            }

            if (filtered.isEmpty()) {
                listWrapper.addView(TextView(this).apply {
                    text = "No transactions SMS found"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 14f
                    setPadding(8, 30, 8, 30)
                })
            } else {
                filtered.forEach { item ->
                    listWrapper.addView(buildSmsItemCard(item))
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                populateFilteredList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        parent.addView(searchInput)

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 24 }
        }
        val filters = listOf("ALL", "Pending", "Success", "Failed")
        for (f in filters) {
            val active = (selectedTxFilter == f)
            val chip = TextView(this).apply {
                text = f
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (active) Color.WHITE else Color.parseColor("#94A3B8"))
                background = boxBg(if (active) "#0284C7" else "#1E293B", "#334155", 18f)
                setPadding(16, 16, 16, 16)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
                setOnClickListener {
                    selectedTxFilter = f
                    renderLoggedInScreen()
                }
            }
            filterRow.addView(chip)
        }
        parent.addView(filterRow)
        parent.addView(listWrapper)
        populateFilteredList()
    }

    @SuppressLint("SetTextI18n")
    private fun buildSmsItemCard(item: SmsRecord): View {
        val statusColor = when (item.status) {
            "Used" -> "#10B981"
            "Success" -> "#38BDF8"
            "Pending" -> "#FACC15"
            else -> "#F87171"
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg("#1E293B", "#334155", 24f)
            setPadding(32, 26, 32, 26)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 18 }
            setOnClickListener { showTransactionDetailsDialog(item) }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "TrxID: ${item.trxId}  •  Tk ${String.format("%.2f", item.amountBdt)}"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = item.status.uppercase()
            setTextColor(Color.parseColor(statusColor))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = boxBg("#0F172A", statusColor, 14f)
            setPadding(18, 6, 18, 6)
        })

        val metaRow = TextView(this).apply {
            text = "Sender: ${item.senderNumber}  |  ${item.dateText} (${item.source})"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, 8, 0, 8)
        }

        val msgRow = TextView(this).apply {
            text = item.message
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            maxLines = 2
        }

        card.addView(headerRow)
        card.addView(metaRow)
        card.addView(msgRow)
        return card
    }

    private fun showTransactionDetailsDialog(item: SmsRecord) {
        val details = StringBuilder().apply {
            append("Transaction ID: ${item.trxId}\\n")
            append("Amount: Tk ${String.format("%.2f", item.amountBdt)}\\n")
            append("From Number: ${item.senderNumber}\\n")
            append("SMS Time: ${item.smsTimeStr}\\n")
            append("Recorded: ${item.dateText}\\n")
            append("Source: ${item.source}\\n")
            append("Status: ${item.status}\\n")
            if (item.serverNote.isNotBlank()) append("Server Note: ${item.serverNote}\\n")
            append("\\nFull SMS Message:\\n${item.message}")
        }.toString()

        AlertDialog.Builder(this)
            .setTitle("Transaction Details")
            .setMessage(details)
            .setPositiveButton("Retry Upload") { _, _ ->
                AppStorage.updateStatusByIdOrTrx(this, item.id, "Pending", "Retrying...")
                SmsReceiver.uploadRecordImmediately(applicationContext, item)
                Toast.makeText(this, "Retrying upload...", Toast.LENGTH_SHORT).show()
                renderLoggedInScreen()
            }
            .setNeutralButton("Copy Message") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SMS", item.message))
                Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Delete") { _, _ ->
                AppStorage.deleteRecord(this, item.id)
                Toast.makeText(this, "Transaction deleted", Toast.LENGTH_SHORT).show()
                renderLoggedInScreen()
            }
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun buildManualEntryView(parent: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg("#1E293B", "#334155", 28f)
            setPadding(36, 36, 36, 36)
        }

        card.addView(TextView(this).apply {
            text = "Add Store Data Manually"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })

        card.addView(TextView(this).apply {
            text = "Paste the full bKash payment SMS below to verify and upload to server."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 8, 0, 24)
        })

        val smsInput = EditText(this).apply {
            hint = "Paste payment SMS message..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            background = boxBg("#0F172A", "#334155", 20f)
            setPadding(28, 24, 28, 24)
        }
        card.addView(smsInput)

        val submitBtn = Button(this).apply {
            text = "🚀 Verify & Upload SMS"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = boxBg("#16A34A", null, 20f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
            setOnClickListener {
                val raw = smsInput.text.toString().trim()
                if (raw.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter SMS text", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val parsed = SmsParser.parseIncomingOrManual("bKash", raw, "Manual")
                if (parsed == null) {
                    Toast.makeText(this@MainActivity, "Invalid bKash SMS format!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                AppStorage.insertIfNew(this@MainActivity, parsed)
                SmsReceiver.uploadRecordImmediately(applicationContext, parsed)
                smsInput.setText("")
                Toast.makeText(this@MainActivity, "Uploading TrxID: ${parsed.trxId}", Toast.LENGTH_SHORT).show()
                currentTab = "TRANSACTIONS"
                renderLoggedInScreen()
            }
        }
        card.addView(submitBtn)
        parent.addView(card)
    }

    @SuppressLint("SetTextI18n")
    private fun buildSettingsView(parent: LinearLayout) {
        val guideCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg("#1E293B", "#CA8A04", 26f)
            setPadding(34, 30, 34, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 24 }
        }
        guideCard.addView(TextView(this).apply {
            text = "🛡️ SmartPay Sync Protection Setup"
            setTextColor(Color.parseColor("#FDE047"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        guideCard.addView(TextView(this).apply {
            text = "• Settings > Apps > SmartPay Sync > Battery usage > Allow background activity ON\\n• Auto Launch / Auto Start: ON\\n• Battery optimization: Don't optimize"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            setPadding(0, 12, 0, 20)
        })
        guideCard.addView(Button(this).apply {
            text = "Allow Unrestricted Battery Optimization"
            setTextColor(Color.WHITE)
            background = boxBg("#CA8A04", null, 18f)
            setOnClickListener { promptBatteryOptimizationIfNeeded(forceOpen = true) }
        })
        parent.addView(guideCard)

        val accountCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg("#1E293B", "#334155", 26f)
            setPadding(34, 30, 34, 30)
        }
        accountCard.addView(TextView(this).apply {
            text = "🔑 Connected Server Info"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        accountCard.addView(TextView(this).apply {
            text = "URL: ${AppStorage.getEndpoint(this@MainActivity)}\\nDevice Key: ${AppStorage.getDeviceKey(this@MainActivity)}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 10, 0, 24)
        })
        accountCard.addView(Button(this).apply {
            text = "🚪 Logout Account"
            setTextColor(Color.WHITE)
            background = boxBg("#DC2626", null, 20f)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Logout Account")
                    .setMessage("Are you sure you want to logout?")
                    .setPositiveButton("Logout") { _, _ ->
                        AppStorage.logout(this@MainActivity)
                        SyncForegroundService.stop(this@MainActivity)
                        renderLoginScreen()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        parent.addView(accountCard)
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    @SuppressLint("BatteryLife")
    private fun promptBatteryOptimizationIfNeeded(forceOpen: Boolean = false) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else if (forceOpen) {
                Toast.makeText(this, "Battery unrestricted mode is already enabled!", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            if (forceOpen) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}