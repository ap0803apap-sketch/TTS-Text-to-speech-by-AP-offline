package com.ap.tts

import android.Manifest
import android.app.Dialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.transition.platform.MaterialFade
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.Executor
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var inputText: TextInputEditText
    private lateinit var charCountText: TextView
    private lateinit var wordCountText: TextView
    private lateinit var sentenceCountText: TextView
    // private lateinit var symbolCountText: TextView // Removed as it's not in layout
    private lateinit var languageSpinner: AutoCompleteTextView
    private lateinit var voiceSpinner: AutoCompleteTextView
    private lateinit var speedSlider: Slider
    private lateinit var pitchSlider: Slider
    private lateinit var volumeSlider: Slider
    private lateinit var speedValueText: TextView
    private lateinit var pitchValueText: TextView
    private lateinit var volumeValueText: TextView
    private lateinit var exportProgressBar: LinearProgressIndicator
    private lateinit var exportProgressText: TextView
    private lateinit var progressContainer: LinearLayout
    private lateinit var radioStart: RadioButton
    private lateinit var radioCursor: RadioButton
    private lateinit var stopSpeakButton: Button
    private lateinit var exportButton: Button
    private lateinit var resetButton: Button
    private lateinit var speakButton: Button
    private lateinit var testVoiceButton: Button
    private lateinit var downloadVoiceButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private lateinit var splashView: View
    private lateinit var mainContainer: ViewGroup

    // Views for text input type
    private lateinit var radioGroupInputType: RadioGroup
    private lateinit var radioPasteText: RadioButton
    private lateinit var radioUploadFile: RadioButton
    private lateinit var selectFileButton: Button
    private lateinit var textInputCard: MaterialCardView
    private lateinit var radioGroupSpeak: RadioGroup
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private var fileContent: String? = null

    private var selectedVoice: Voice? = null
    private var selectedLanguage: Locale = Locale.Builder().setLanguage("hi").setRegion("IN").build()
    private var isExporting = false

    private var availableLocales: List<Locale> = emptyList()
    private var availableVoices: List<Voice> = emptyList()
    private var languageDisplayNames = ArrayList<String>()
    private var voiceDisplayNames = ArrayList<String>()

    companion object {
        private const val PREFS_NAME = "TtsPrefs"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_AMOLED_MODE = "amoled_mode"
        private const val GOOGLE_TTS_VOICE_DATA_PACKAGE = "com.google.android.apps.speech.tts"
        private const val GOOGLE_TTS_VOICE_DATA_ACTIVITY = "com.google.android.apps.speech.tts.googletts.local.voicepack.ui.VoiceDataInstallActivity"
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TtsService.NOTIFICATION_PROGRESS -> {
                    val progress = intent.getIntExtra(TtsService.EXTRA_PROGRESS, 0)
                    val formattedText = intent.getStringExtra(TtsService.EXTRA_TIME_REMAINING_FORMATTED) ?: ""

                    exportProgressBar.isIndeterminate = false
                    // Expressive: Animate progress bar updates automatically via Material UI
                    exportProgressBar.setProgressCompat(progress, true)
                    exportProgressText.text = formattedText
                }
                TtsService.NOTIFICATION_COMPLETE -> {
                    val message = intent.getStringExtra(TtsService.EXTRA_MESSAGE)
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({
                        resetForNextConversion()
                    }, 1000)
                }
                TtsService.NOTIFICATION_ERROR -> {
                    val message = intent.getStringExtra(TtsService.EXTRA_MESSAGE)
                    Toast.makeText(this@MainActivity, "Error: $message", Toast.LENGTH_LONG).show()
                    resetForNextConversion()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applyAppTheme()

        // 🎨 EXPRESSIVE M3: Enable Edge-to-Edge drawing
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)
        if (sharedPreferences.getBoolean(KEY_DYNAMIC_COLOR, true)) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        setContentView(R.layout.activity_main)

        bindViews()

        // 🎨 EXPRESSIVE M3: Handle System Insets to prevent overlapping with transparent status/nav bars
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Add navigation bar height to existing bottom padding
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom + 100)
            insets
        }

        tts = TextToSpeech(this, this)
        setupListeners()
        loadSettings()

        initFilePicker()
        setupInputTypeListeners()

        askNotificationPermission()

        if (savedInstanceState != null) {
            isExporting = savedInstanceState.getBoolean("isExporting", false)
            val isPasteMode = savedInstanceState.getBoolean("isPasteMode", false)
            radioPasteText.isChecked = isPasteMode
            radioUploadFile.isChecked = !isPasteMode
            updateUiForInputType(isPasteMode)
        } else {
            radioUploadFile.isChecked = true
            updateUiForInputType(false)
        }

        handleSharedIntent(intent)
    }

    private fun applyAppTheme() {
        val themeMode = sharedPreferences.getInt(KEY_THEME, 0)
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        val isDark = when (themeMode) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        if (sharedPreferences.getBoolean(KEY_AMOLED_MODE, false) && isDark) {
            setTheme(R.style.Theme_TTSByAP_Amoled)
        } else {
            setTheme(R.style.Theme_TTSByAP)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        if (sharedPreferences.getBoolean(KEY_BIOMETRIC_ENABLED, false)) {
            mainContainer.visibility = View.INVISIBLE
            splashView.visibility = View.VISIBLE
            showBiometricPromptForAppEntry()
        } else {
            mainContainer.visibility = View.VISIBLE
            splashView.visibility = View.GONE
        }

        val filter = IntentFilter().apply {
            addAction(TtsService.NOTIFICATION_PROGRESS)
            addAction(TtsService.NOTIFICATION_COMPLETE)
            addAction(TtsService.NOTIFICATION_ERROR)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, filter)

        if (TtsService.isRunning) {
            isExporting = true
            updateUiForExport(true)
        } else if (isExporting) {
            isExporting = false
            updateUiForExport(false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    private fun showBiometricPromptForAppEntry() {
        executor = ContextCompat.getMainExecutor(this)
        val biometricManager = BiometricManager.from(this)

        when (biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                biometricPrompt = BiometricPrompt(this, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            // 🎨 EXPRESSIVE M3: Fade transition for biometric unlock
                            val transition = MaterialFade()
                            android.transition.TransitionManager.beginDelayedTransition(findViewById(android.R.id.content), transition)

                            mainContainer.visibility = View.VISIBLE
                            splashView.visibility = View.GONE
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                            finish()
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    })

                promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Biometric Lock")
                    .setSubtitle("Log in using your biometric credential")
                    .setNegativeButtonText("Cancel")
                    .build()

                biometricPrompt.authenticate(promptInfo)
            }
            else -> {
                Toast.makeText(this, "Biometric authentication is not available or not set up.", Toast.LENGTH_LONG).show()
                mainContainer.visibility = View.VISIBLE
                splashView.visibility = View.GONE
            }
        }
    }

    private fun showBiometricPromptForSwitch(targetState: Boolean, onResult: (Boolean) -> Unit) {
        executor = ContextCompat.getMainExecutor(this)
        val biometricManager = BiometricManager.from(this)

        when (biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                biometricPrompt = BiometricPrompt(this, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            sharedPreferences.edit().putBoolean(KEY_BIOMETRIC_ENABLED, targetState).apply()
                            onResult(true)
                            Toast.makeText(applicationContext, "Biometric lock ${if(targetState) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            onResult(false)
                            Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                        }
                    })

                promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Confirm Action")
                    .setSubtitle("Authenticate to change biometric lock setting")
                    .setNegativeButtonText("Cancel")
                    .build()

                biometricPrompt.authenticate(promptInfo)
            }
            else -> {
                Toast.makeText(this, "Biometric authentication is not set up.", Toast.LENGTH_LONG).show()
                onResult(false)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isExporting", isExporting)
        if (this::inputText.isInitialized) {
            outState.putString("inputText", inputText.text.toString())
        }
        if (this::radioPasteText.isInitialized) {
            outState.putBoolean("isPasteMode", radioPasteText.isChecked)
        }
    }

    private fun bindViews() {
        splashView = findViewById(R.id.splashView)
        mainContainer = findViewById(R.id.mainContainer)
        inputText = findViewById(R.id.inputText)
        charCountText = findViewById(R.id.charCountText)
        wordCountText = findViewById(R.id.wordCountText)
        sentenceCountText = findViewById(R.id.sentenceCountText)
        // symbolCountText = findViewById(R.id.symbolCountText) // Removed
        languageSpinner = findViewById(R.id.languageSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        speedSlider = findViewById(R.id.speedSlider)
        pitchSlider = findViewById(R.id.pitchSlider)
        volumeSlider = findViewById(R.id.volumeSlider)
        speedValueText = findViewById(R.id.speedValueText)
        pitchValueText = findViewById(R.id.pitchValueText)
        volumeValueText = findViewById(R.id.volumeValueText)
        progressContainer = findViewById(R.id.progressContainer)
        exportProgressBar = findViewById(R.id.exportProgressBar)
        exportProgressText = findViewById(R.id.exportProgressText)
        radioStart = findViewById(R.id.radioStart)
        radioCursor = findViewById(R.id.radioCursor)
        stopSpeakButton = findViewById(R.id.stopSpeakButton)
        exportButton = findViewById(R.id.exportButton)
        resetButton = findViewById(R.id.resetButton)
        speakButton = findViewById(R.id.speakButton)
        testVoiceButton = findViewById(R.id.testVoiceButton)
        downloadVoiceButton = findViewById(R.id.downvoice)
        settingsButton = findViewById(R.id.settingsButton)
        radioGroupInputType = findViewById(R.id.radioGroupInputType)
        radioPasteText = findViewById(R.id.radioPasteText)
        radioUploadFile = findViewById(R.id.radioUploadFile)
        selectFileButton = findViewById(R.id.selectFileButton)
        textInputCard = findViewById(R.id.textInputCard)
        radioGroupSpeak = findViewById(R.id.radioGroupSpeakFrom)

        updateTextAnalysis("")
    }

    private fun setupListeners() {
        inputText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateTextAnalysis(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        speedSlider.addOnChangeListener { _, value, _ -> speedValueText.text = String.format(Locale.US, "%.2fx", value / 50f) }
        pitchSlider.addOnChangeListener { _, value, _ -> pitchValueText.text = String.format(Locale.US, "%d%%", value.toInt()) }
        volumeSlider.addOnChangeListener { _, value, _ -> volumeValueText.text = String.format(Locale.US, "%d%%", value.toInt()) }

        settingsButton.setOnClickListener { showSettingsBottomSheet() }

        findViewById<Button>(R.id.copyButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TTS Text", inputText.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.pasteButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val pasteData = clipboard.primaryClip?.getItemAt(0)?.text.toString()
                inputText.append(pasteData)
                Toast.makeText(this, "Text pasted", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            inputText.setText("")
            fileContent = null
            Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
        }
        resetButton.setOnClickListener { resetToDefaults() }
        speakButton.setOnClickListener { speakText() }
        testVoiceButton.setOnClickListener { speakText(getString(R.string.test_voice)) }
        exportButton.setOnClickListener { startExport() }

        stopSpeakButton.setOnClickListener {
            if (isExporting) {
                val stopIntent = Intent(this, TtsService::class.java).apply { action = TtsService.ACTION_STOP_EXPORT }
                startService(stopIntent)
            } else {
                tts.stop()
            }
        }

        languageSpinner.setOnClickListener {
            showSearchableDialog(getString(R.string.select_language), languageDisplayNames) { selectedItem ->
                val originalIndex = languageDisplayNames.indexOf(selectedItem)
                if (originalIndex != -1) {
                    languageSpinner.setText(selectedItem, false)
                    selectedLanguage = availableLocales[originalIndex]
                    tts.language = selectedLanguage
                    updateVoiceSpinner()
                }
            }
        }

        voiceSpinner.setOnClickListener {
            showSearchableDialog(getString(R.string.select_voice), voiceDisplayNames) { selectedItem ->
                val originalIndex = voiceDisplayNames.indexOf(selectedItem)
                if (originalIndex != -1) {
                    voiceSpinner.setText(selectedItem, false)
                    selectedVoice = availableVoices[originalIndex]
                    tts.voice = selectedVoice
                    updateVoiceSpinner()
                }
            }
        }

        downloadVoiceButton.setOnClickListener {
            startGoogleVoiceDataInstall()
        }
    }

    private fun showSettingsBottomSheet() {
        // 🎨 EXPRESSIVE M3: Ensures BottomSheet embraces modern system themes automatically
        val dialog = BottomSheetDialog(this, R.style.Theme_TTSByAP_BottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.layout_settings_bottom_sheet, null)
        dialog.setContentView(view)

        val themeGroup = view.findViewById<RadioGroup>(R.id.themeRadioGroup)
        val dynamicSwitch = view.findViewById<MaterialSwitch>(R.id.dynamicColorSwitch)
        val amoledSwitch = view.findViewById<MaterialSwitch>(R.id.amoledSwitch)
        val bsBiometricSwitch = view.findViewById<MaterialSwitch>(R.id.bsBiometricSwitch)
        val githubLink = view.findViewById<TextView>(R.id.githubLink)

        when (sharedPreferences.getInt(KEY_THEME, 0)) {
            0 -> themeGroup.check(R.id.themeSystem)
            1 -> themeGroup.check(R.id.themeLight)
            2 -> themeGroup.check(R.id.themeDark)
        }
        dynamicSwitch.isChecked = sharedPreferences.getBoolean(KEY_DYNAMIC_COLOR, true)
        amoledSwitch.isChecked = sharedPreferences.getBoolean(KEY_AMOLED_MODE, false)
        bsBiometricSwitch.isChecked = sharedPreferences.getBoolean(KEY_BIOMETRIC_ENABLED, false)

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> 1
                R.id.themeDark -> 2
                else -> 0
            }
            sharedPreferences.edit().putInt(KEY_THEME, mode).apply()
            applyAppTheme()
            recreate()
        }

        dynamicSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_DYNAMIC_COLOR, isChecked).apply()
            recreate()
        }

        amoledSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_AMOLED_MODE, isChecked).apply()
            recreate()
        }

        bsBiometricSwitch.setOnClickListener {
            val target = !sharedPreferences.getBoolean(KEY_BIOMETRIC_ENABLED, false)
            showBiometricPromptForSwitch(target) { success ->
                if (!success) bsBiometricSwitch.isChecked = !target
            }
        }

        view.findViewById<TextView>(R.id.devEmail).setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:ap0803apap@gmail.com")
                putExtra(Intent.EXTRA_SUBJECT, "TTS by AP Feedback")
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
            }
        }

        githubLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ap0803apap-sketch/"))
            startActivity(intent)
        }

        dialog.show()
    }

    private fun initFilePicker() {
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                fileContent = readTextFromUri(it)
                inputText.setText(fileContent)

                val fileName = getFileName(it)
                selectFileButton.text = getString(R.string.selected_file, fileName)
                Toast.makeText(this, "File loaded. Size: ${fileContent?.length} chars", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result = "Unknown File"
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            result = cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error getting file name: ${e.message}")
            }
        }
        if (result == "Unknown File" && uri.scheme == "file") {
            result = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            } ?: "Unknown File"
        }
        return result
    }

    private fun setupInputTypeListeners() {
        radioGroupInputType.setOnCheckedChangeListener { _, checkedId ->
            updateUiForInputType(checkedId == R.id.radioPasteText)
        }
        selectFileButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("text/plain", "application/txt"))
        }
    }

    private fun updateUiForInputType(isPasteMode: Boolean) {
        // 🎨 EXPRESSIVE M3: Use MaterialFade for beautiful, soft component transitions
        val transition = MaterialFade().apply {
            duration = 250L
        }
        android.transition.TransitionManager.beginDelayedTransition(mainContainer, transition)

        textInputCard.alpha = if (isPasteMode) 1.0f else 0.5f
        inputText.isEnabled = isPasteMode

        (textInputCard.getChildAt(0) as? ViewGroup)?.let { layout ->
            for (i in 0 until layout.childCount) {
                if (layout.getChildAt(i) !is TextView && layout.getChildAt(i) !is TextInputLayout) {
                    layout.getChildAt(i).isEnabled = isPasteMode
                }
                if (layout.getChildAt(i) is TextInputLayout) {
                    layout.getChildAt(i).isEnabled = isPasteMode
                }
            }
        }
        findViewById<Button>(R.id.copyButton).isEnabled = isPasteMode
        findViewById<Button>(R.id.pasteButton).isEnabled = isPasteMode
        findViewById<Button>(R.id.clearButton).isEnabled = true

        selectFileButton.isEnabled = !isPasteMode

        radioGroupSpeak.alpha = if (isPasteMode) 1.0f else 0.5f
        for (i in 0 until radioGroupSpeak.childCount) {
            radioGroupSpeak.getChildAt(i).isEnabled = isPasteMode
        }

        if (!isPasteMode) {
            radioStart.isChecked = true
            if (fileContent == null) {
                inputText.setText("")
                selectFileButton.setText(R.string.select_file)
            } else {
                inputText.setText(fileContent)
            }
        } else {
            fileContent = null
            selectFileButton.setText(R.string.select_file)
        }
        updateTextAnalysis(inputText.text.toString())
    }

    @Throws(IOException::class)
    private fun readTextFromUri(uri: Uri): String {
        val stringBuilder = StringBuilder()
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stringBuilder.append(line).append('\n')
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        return stringBuilder.toString()
    }

    private fun handleSharedIntent(intent: Intent) {
        var handled = false

        if (intent.action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }

            streamUri?.let { uri ->
                if (intent.type?.startsWith("text/") == true || intent.type == "application/txt" || intent.type == "application/octet-stream") {
                    fileContent = readTextFromUri(uri)
                    inputText.setText(fileContent)
                    switchToUploadMode(uri)
                    handled = true
                }
            }

            if (!handled && intent.type == "text/plain") {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                    fileContent = it
                    inputText.setText(it)
                    switchToUploadMode(Uri.parse("text://shared"))
                    handled = true
                }
            }
        }

        if (intent.action == Intent.ACTION_SEND_MULTIPLE && !handled) {
            val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM) as? ArrayList<Parcelable>
            }

            streamUris?.filterIsInstance<Uri>()
                ?.firstOrNull()
                ?.let { uri ->
                    fileContent = readTextFromUri(uri)
                    inputText.setText(fileContent)
                    switchToUploadMode(uri)
                }
        }
    }

    private fun switchToUploadMode(uri: Uri? = null) {
        radioUploadFile.isChecked = true
        updateUiForInputType(isPasteMode = false)

        val fileName = if (uri != null && uri.scheme != "text") {
            getFileName(uri)
        } else {
            "Shared Text"
        }
        selectFileButton.text = getString(R.string.selected_file, fileName)
        Toast.makeText(this, "Shared file loaded", Toast.LENGTH_SHORT).show()
    }

    private fun updateTextAnalysis(text: String) {
        charCountText.text = getString(R.string.letters_count, text.length)
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        wordCountText.text = "Words: ${words.size}"
        val sentences = text.split(Regex("[.!?]+")).filter { it.isNotBlank() }
        sentenceCountText.text = "Sentences: ${sentences.size}"
        // val symbols = text.filter { !it.isLetterOrDigit() && !it.isWhitespace() }
        // symbolCountText.text = "Symbols: ${symbols.length}"
    }

    private fun loadSettings() {
    }

    private fun startGoogleVoiceDataInstall() {
        Toast.makeText(this, "Opening Google TTS voice data settings...", Toast.LENGTH_SHORT).show()
        val installIntent = Intent()
        installIntent.component = ComponentName(GOOGLE_TTS_VOICE_DATA_PACKAGE, GOOGLE_TTS_VOICE_DATA_ACTIVITY)
        installIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            startActivity(installIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e("MainActivity", "Google TTS Voice Data activity not found: $e")
            try {
                val genericIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                startActivity(genericIntent)
            } catch (e2: ActivityNotFoundException) {
                Log.e("MainActivity", "Generic TTS Voice Data activity not found: $e2")
                Toast.makeText(this, "Could not open voice data settings. Please check your Text-to-speech settings manually.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSearchableDialog(title: String, data: ArrayList<String>, onItemSelected: (String) -> Unit) {
        // 🎨 EXPRESSIVE M3: Using a rounded dialog with smooth styling properties
        val dialog = Dialog(this, R.style.Theme_TTSByAP_MaterialAlertDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_searchable_spinner)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val searchEditText = dialog.findViewById<EditText>(R.id.searchEditText)
        val listView = dialog.findViewById<ListView>(R.id.listView)

        dialogTitle.text = title

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, data)
        listView.adapter = adapter

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { adapter.filter.filter(s) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { onItemSelected(it) }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun speakText(textToSpeak: String? = null) {
        val text = textToSpeak ?: if (radioPasteText.isChecked) {
            inputText.text.toString()
        } else {
            fileContent ?: inputText.text.toString()
        }

        if (text.isBlank()) {
            Toast.makeText(this, "No text to speak.", Toast.LENGTH_SHORT).show()
            return
        }

        tts.setPitch(pitchSlider.value / 100f * 2f)
        tts.setSpeechRate(speedSlider.value / 50f)

        val textToUse: String
        if (radioUploadFile.isChecked) {
            textToUse = text
        } else if (radioCursor.isChecked) {
            val cursorPos = inputText.selectionStart
            textToUse = if (cursorPos >= 0 && cursorPos < text.length) text.substring(cursorPos) else text
        } else {
            textToUse = text
        }

        tts.speak(textToUse, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun startExport() {
        val fullText = if (radioPasteText.isChecked) {
            inputText.text.toString()
        } else {
            fileContent ?: inputText.text.toString()
        }

        if (fullText.isBlank()) {
            Toast.makeText(this, "No text to export.", Toast.LENGTH_SHORT).show()
            return
        }

        val textToExport: String
        if (radioUploadFile.isChecked) {
            textToExport = fullText
        } else if (radioCursor.isChecked) {
            val cursorPos = inputText.selectionStart
            textToExport = if (cursorPos >= 0 && cursorPos < fullText.length) fullText.substring(cursorPos) else fullText
        } else {
            textToExport = fullText
        }

        if (textToExport.isBlank()) {
            Toast.makeText(this, "No text to export from cursor.", Toast.LENGTH_SHORT).show()
            return
        }

        isExporting = true
        updateUiForExport(true)

        val intent = Intent(this, TtsService::class.java).apply {
            action = TtsService.ACTION_START_EXPORT
            putExtra(TtsService.EXTRA_TEXT, textToExport)
            putExtra(TtsService.EXTRA_PITCH, pitchSlider.value / 100f * 2f)
            putExtra(TtsService.EXTRA_SPEED, speedSlider.value / 50f)
            putExtra(TtsService.EXTRA_VOLUME, volumeSlider.value / 100f)
            putExtra(TtsService.EXTRA_LANGUAGE, selectedLanguage)
            putExtra(TtsService.EXTRA_VOICE_NAME, selectedVoice?.name)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun resetForNextConversion() {
        isExporting = false
        updateUiForExport(false)
    }

    private fun resetToDefaults() {
        inputText.setText("")
        radioStart.isChecked = true
        speedSlider.value = 50f
        pitchSlider.value = 50f
        volumeSlider.value = 100f

        radioUploadFile.isChecked = true
        fileContent = null

        selectFileButton.setText(R.string.select_file)

        updateUiForInputType(isPasteMode = false)
        setupLanguageAndVoiceSpinners()
    }

    private fun updateUiForExport(isExporting: Boolean) {
        // 🎨 EXPRESSIVE M3: Fluid layout morphing with Material transitions
        val transition = MaterialFade().apply { duration = 300L }
        android.transition.TransitionManager.beginDelayedTransition(mainContainer, transition)

        val enabled = !isExporting

        val controlsToToggle = listOf(
            exportButton, resetButton, speakButton, testVoiceButton,
            languageSpinner, voiceSpinner, speedSlider, pitchSlider, volumeSlider,
            radioGroupInputType,
            selectFileButton
        )

        controlsToToggle.forEach { it.isEnabled = enabled }

        if (isExporting) {
            textInputCard.alpha = 0.5f
            inputText.isEnabled = false
            findViewById<Button>(R.id.copyButton).isEnabled = false
            findViewById<Button>(R.id.pasteButton).isEnabled = false
            findViewById<Button>(R.id.clearButton).isEnabled = false

            radioGroupSpeak.alpha = 0.5f
            for (i in 0 until radioGroupSpeak.childCount) {
                radioGroupSpeak.getChildAt(i).isEnabled = false
            }
            downloadVoiceButton.isEnabled = true
        } else {
            updateUiForInputType(isPasteMode = radioPasteText.isChecked)
        }

        findViewById<View>(R.id.progressCard).visibility = if (isExporting) View.VISIBLE else View.GONE
        if (isExporting) {
            exportProgressBar.isIndeterminate = true
            exportProgressText.text = getString(R.string.exporting_preparing)
        }

        stopSpeakButton.text = if (isExporting) getString(R.string.cancel_export) else getString(R.string.stop_speak)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            setupLanguageAndVoiceSpinners()
        } else {
            Toast.makeText(this, "TTS Initialization failed", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupLanguageAndVoiceSpinners() {
        availableLocales = tts.availableLanguages?.toList()?.sortedBy { it.displayName } ?: emptyList()
        languageDisplayNames.clear()
        languageDisplayNames.addAll(availableLocales.map { it.displayName })

        val initialLangIndex = availableLocales.indexOf(selectedLanguage)
        if (initialLangIndex != -1) {
            languageSpinner.setText(availableLocales[initialLangIndex].displayName, false)
        } else if (availableLocales.isNotEmpty()) {
            selectedLanguage = availableLocales[0]
            languageSpinner.setText(selectedLanguage.displayName, false)
        } else {
            languageSpinner.setText("No Languages", false)
        }
        updateVoiceSpinner()
    }

    private fun updateVoiceSpinner() {
        availableVoices = tts.voices?.filter { it.locale == selectedLanguage }?.sortedBy { it.name } ?: emptyList()
        voiceDisplayNames.clear()
        voiceDisplayNames.addAll(availableVoices.map { it.name })

        if (availableVoices.isNotEmpty()) {
            val currentVoiceIndex = availableVoices.indexOf(selectedVoice).takeIf { it != -1 } ?: 0
            selectedVoice = availableVoices[currentVoiceIndex]
            tts.voice = selectedVoice
            voiceSpinner.setText(selectedVoice?.name, false)
        } else {
            selectedVoice = null
            voiceSpinner.setText("No voices available", false)
        }

        val langCheckResult = tts.isLanguageAvailable(selectedLanguage)
        val isLanguageMissingData = langCheckResult == TextToSpeech.LANG_MISSING_DATA || langCheckResult == TextToSpeech.LANG_NOT_SUPPORTED
        val isVoiceNotInstalled = selectedVoice?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true

        val shouldEnable = !(isLanguageMissingData || isVoiceNotInstalled) && !isExporting

        testVoiceButton.isEnabled = shouldEnable
        speakButton.isEnabled = shouldEnable
        exportButton.isEnabled = shouldEnable
        downloadVoiceButton.isEnabled = true
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver)
    }

    override fun onDestroy() {
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}