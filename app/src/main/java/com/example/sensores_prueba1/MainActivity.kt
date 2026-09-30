package com.example.sensores_prueba1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sensores_prueba1.data.model.BatonData
import com.example.sensores_prueba1.nfc.BatonManager
import com.example.sensores_prueba1.nfc.HandoffEvent
import com.example.sensores_prueba1.nfc.RelayBatonReader
import com.example.sensores_prueba1.sensors.GpsTracker
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Controlador del Lector NFC y GPS
    private lateinit var relayBatonReader: RelayBatonReader
    private lateinit var gpsTracker: GpsTracker
    private var isNfcReaderManualScanning = false

    // Gestión de Sensores
    private lateinit var sensorManager: SensorManager
    private var stepDetectorSensor: Sensor? = null
    private var stepCounterSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    // Estado de Telemetría Dinámica y Calibración
    private var totalSteps = 0
    private var initialStepCounterValue = -1
    private var lastAccelerometerStepTime = 0L
    private var lastStepDetectedTime = 0L
    private var accumulatedIndoorDistanceMeters = 0f
    private var smoothedCadence = 0f
    private val accelFilterAlpha = 0.82f
    private var filteredAccel = SensorManager.GRAVITY_EARTH

    // Variables de Calibración
    private var lastAccMagnitude = SensorManager.GRAVITY_EARTH
    private var baselineGravity = SensorManager.GRAVITY_EARTH
    private var sensorNoiseThreshold = 0.5f
    private var isCalibrated = false
    private var isCalibrating = false

    // Estado del Cronómetro de Carrera
    private enum class TimerState { STOPPED, RUNNING, PAUSED }
    private var timerState = TimerState.STOPPED
    private var timerStartTime = 0L
    private var accumulatedTimeMs = 0L
    private var timerJob: Job? = null

    // Referencias de Vistas UI
    private lateinit var tvRaceId: TextView
    private lateinit var tvTeamName: TextView
    private lateinit var tvLegBadge: TextView
    private lateinit var tvRunnerName: TextView
    private lateinit var btnSettingsHeader: MaterialButton
    private lateinit var btnCalibrateHeader: MaterialButton

    private lateinit var tvTimerMain: TextView
    private lateinit var tvTimerStatus: TextView
    private lateinit var tvTimerSubtext: TextView

    private lateinit var cardBatonStatus: MaterialCardView
    private lateinit var ivBatonStatusIcon: ImageView
    private lateinit var tvBatonStatusTitle: TextView
    private lateinit var tvBatonStatusDesc: TextView
    private lateinit var tvBatonDetails: TextView

    private lateinit var tvCadenceValue: TextView
    private lateinit var tvDistanceValue: TextView
    private lateinit var tvPaceValue: TextView
    private lateinit var tvStepCountValue: TextView
    private lateinit var tvSensorStatus: TextView

    private lateinit var cardNfcAction: MaterialCardView
    private lateinit var viewNfcPulseRing: android.view.View
    private lateinit var ivNfcIcon: ImageView
    private lateinit var tvNfcTitle: TextView
    private lateinit var tvNfcSubtitle: TextView
    private lateinit var tvNfcHandoffLog: TextView
    private lateinit var btnToggleNfcScan: MaterialButton

    private lateinit var btnStartPause: MaterialButton
    private lateinit var btnStopReset: MaterialButton
    private lateinit var btnPassBaton: MaterialButton

    private var pulseAnimation: Animation? = null

    // Lanzador de Permisos (Actividad Física + Ubicación GPS)
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        registerSensors()
        val hasGps = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasGps && timerState == TimerState.RUNNING) {
            gpsTracker.startTracking()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Forzar iconos claros (blancos) en la barra de estado y de navegación sobre el fondo oscuro
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        initAnimations()
        initNfcReader()
        initSensors()
        setupClickListeners()
        observeBatonState()
        checkPermissionsAndStartSensors()
    }

    private fun bindViews() {
        tvRaceId = findViewById(R.id.tvRaceId)
        tvTeamName = findViewById(R.id.tvTeamName)
        tvLegBadge = findViewById(R.id.tvLegBadge)
        tvRunnerName = findViewById(R.id.tvRunnerName)
        btnSettingsHeader = findViewById(R.id.btnSettingsHeader)
        btnCalibrateHeader = findViewById(R.id.btnCalibrateHeader)

        tvTimerMain = findViewById(R.id.tvTimerMain)
        tvTimerStatus = findViewById(R.id.tvTimerStatus)
        tvTimerSubtext = findViewById(R.id.tvTimerSubtext)

        cardBatonStatus = findViewById(R.id.cardBatonStatus)
        ivBatonStatusIcon = findViewById(R.id.ivBatonStatusIcon)
        tvBatonStatusTitle = findViewById(R.id.tvBatonStatusTitle)
        tvBatonStatusDesc = findViewById(R.id.tvBatonStatusDesc)
        tvBatonDetails = findViewById(R.id.tvBatonDetails)

        tvCadenceValue = findViewById(R.id.tvCadenceValue)
        tvDistanceValue = findViewById(R.id.tvDistanceValue)
        tvPaceValue = findViewById(R.id.tvPaceValue)
        tvStepCountValue = findViewById(R.id.tvStepCountValue)
        tvSensorStatus = findViewById(R.id.tvSensorStatus)

        cardNfcAction = findViewById(R.id.cardNfcAction)
        viewNfcPulseRing = findViewById(R.id.viewNfcPulseRing)
        ivNfcIcon = findViewById(R.id.ivNfcIcon)
        tvNfcTitle = findViewById(R.id.tvNfcTitle)
        tvNfcSubtitle = findViewById(R.id.tvNfcSubtitle)
        tvNfcHandoffLog = findViewById(R.id.tvNfcHandoffLog)
        btnToggleNfcScan = findViewById(R.id.btnToggleNfcScan)

        btnStartPause = findViewById(R.id.btnStartPause)
        btnStopReset = findViewById(R.id.btnStopReset)
        btnPassBaton = findViewById(R.id.btnPassBaton)
    }

    private fun initAnimations() {
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse_ring)
    }

    private fun initNfcReader() {
        relayBatonReader = RelayBatonReader(this)
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gpsTracker = GpsTracker(this)
    }

    private fun checkPermissionsAndStartSensors() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            registerSensors()
        }
    }

    private fun registerSensors() {
        var registered = false
        stepDetectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            registered = true
        }
        stepCounterSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            registered = true
        }
        accelerometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            registered = true
        }

        val statusStr = when {
            isCalibrated -> "● Calibrado (GPS + Inercial)"
            registered -> "● Sensores Activos"
            else -> "● En Espera"
        }
        tvSensorStatus.text = statusStr
        tvSensorStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (registered) R.color.accent_green else R.color.text_dim
            )
        )
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
        gpsTracker.stopTracking()
    }

    private fun setupClickListeners() {
        btnSettingsHeader.setOnClickListener {
            performTactileClick()
            showRaceSettingsDialog()
        }

        btnCalibrateHeader.setOnClickListener {
            performTactileClick()
            showSensorCalibrationDialog()
        }

        btnStartPause.setOnClickListener {
            performTactileClick()
            when (timerState) {
                TimerState.STOPPED, TimerState.PAUSED -> {
                    if (!isCalibrated) {
                        Toast.makeText(this, "💡 Consejo: Calibra con el icono superior para mayor precisión", Toast.LENGTH_SHORT).show()
                    }
                    startRaceTimer()
                }
                TimerState.RUNNING -> pauseRaceTimer()
            }
        }

        btnStopReset.setOnClickListener {
            performTactileClick()
            resetRace()
        }

        btnPassBaton.setOnClickListener {
            performTactileClick()
            triggerManualBatonHandoff()
        }

        btnToggleNfcScan.setOnClickListener {
            performTactileClick()
            toggleManualNfcScanning()
        }
    }

    // ==========================================
    // DIÁLOGOS DE CONFIGURACIÓN Y CALIBRACIÓN
    // ==========================================

    private fun showRaceSettingsDialog() {
        val currentBaton = BatonManager.currentBaton.value
        val isCarrying = BatonManager.isCarryingBaton.value

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_race_settings, null)
        val etRunnerName = dialogView.findViewById<TextInputEditText>(R.id.etRunnerName)
        val etTeamId = dialogView.findViewById<TextInputEditText>(R.id.etTeamId)
        val etRaceId = dialogView.findViewById<TextInputEditText>(R.id.etRaceId)
        val tvLegIndexValue = dialogView.findViewById<TextView>(R.id.tvLegIndexValue)
        val btnLegMinus = dialogView.findViewById<MaterialButton>(R.id.btnLegMinus)
        val btnLegPlus = dialogView.findViewById<MaterialButton>(R.id.btnLegPlus)
        val switchCarrying = dialogView.findViewById<MaterialSwitch>(R.id.switchCarrying)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelSettings)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveSettings)

        var selectedLeg = currentBaton?.legIndex ?: 1
        etRunnerName.setText(currentBaton?.runnerName ?: "Corredor 1")
        etTeamId.setText(currentBaton?.teamId ?: "EQUIPO-ALFA")
        etRaceId.setText(currentBaton?.raceId ?: "CARRERA-2026-ALFA")
        tvLegIndexValue.text = selectedLeg.toString()
        switchCarrying.isChecked = isCarrying

        btnLegMinus.setOnClickListener {
            if (selectedLeg > 1) {
                selectedLeg--
                tvLegIndexValue.text = selectedLeg.toString()
            }
        }

        btnLegPlus.setOnClickListener {
            if (selectedLeg < 12) {
                selectedLeg++
                tvLegIndexValue.text = selectedLeg.toString()
            }
        }

        val alertDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener {
            alertDialog.dismiss()
        }

        btnSave.setOnClickListener {
            val runner = etRunnerName.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Corredor $selectedLeg"
            val team = etTeamId.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "EQUIPO-ALFA"
            val race = etRaceId.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "CARRERA-2026-ALFA"
            val carrying = switchCarrying.isChecked

            val updatedBaton = BatonData(
                raceId = race,
                teamId = team,
                legIndex = selectedLeg,
                runnerName = runner,
                timestampMs = System.currentTimeMillis(),
                signatureToken = "SIG-CONF-$selectedLeg-${System.currentTimeMillis() % 1000}"
            )

            BatonManager.updateBaton(updatedBaton, carrying = carrying)
            performTactileClick()
            Toast.makeText(this, "✅ Configuración de carrera guardada", Toast.LENGTH_SHORT).show()
            alertDialog.dismiss()
        }

        alertDialog.show()
    }

    private fun showSensorCalibrationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sensor_calibration, null)
        val progressCalibration = dialogView.findViewById<CircularProgressIndicator>(R.id.progressCalibration)
        val tvCountdown = dialogView.findViewById<TextView>(R.id.tvCalibrationCountdown)
        val tvTelemetry = dialogView.findViewById<TextView>(R.id.tvCalibrationTelemetry)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelCalibration)
        val btnStart = dialogView.findViewById<MaterialButton>(R.id.btnStartCalibration)

        var calibrationJob: Job? = null

        val alertDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            calibrationJob?.cancel()
            isCalibrating = false
            alertDialog.dismiss()
        }

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            btnCancel.isEnabled = false
            isCalibrating = true

            calibrationJob = lifecycleScope.launch {
                val samples = mutableListOf<Float>()
                val durationMs = 3000L
                val intervalMs = 50L
                val totalTicks = (durationMs / intervalMs).toInt()

                tvCountdown.text = "Mantén el dispositivo firme..."

                for (i in 1..totalTicks) {
                    delay(intervalMs)
                    val progress = (i * 100) / totalTicks
                    progressCalibration.progress = progress

                    val remainingSeconds = String.format(Locale.US, "%.1fs", (durationMs - (i * intervalMs)) / 1000.0)
                    tvCountdown.text = "Calibrando... $remainingSeconds"

                    // Muestrear magnitud instantánea
                    samples.add(lastAccMagnitude)
                    tvTelemetry.text = String.format(Locale.US, "Muestra: %.2f m/s² (N=%d)", lastAccMagnitude, samples.size)
                }

                if (samples.isNotEmpty()) {
                    val avgMag = samples.average().toFloat()
                    val variance = samples.map { (it - avgMag) * (it - avgMag) }.average().toFloat()
                    val stdDev = sqrt(variance)

                    baselineGravity = if (avgMag in 8.0f..12.0f) avgMag else SensorManager.GRAVITY_EARTH
                    sensorNoiseThreshold = maxOf(0.35f, stdDev * 2.2f)
                    filteredAccel = baselineGravity
                    isCalibrated = true

                    // Resetear contadores espurios
                    initialStepCounterValue = -1
                    smoothedCadence = 0f
                    lastStepDetectedTime = 0L

                    tvCountdown.text = "✅ ¡Calibración exitosa!"
                    tvTelemetry.text = String.format(
                        Locale.US,
                        "Gravedad basal: %.2f m/s² • Ruido: ±%.2f",
                        baselineGravity,
                        sensorNoiseThreshold
                    )
                    vibrateHandoffSent()
                    tvSensorStatus.text = "● Calibrado"
                    tvSensorStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_cyan))

                    delay(800L)
                }

                isCalibrating = false
                btnCancel.isEnabled = true
                btnCancel.text = "Aceptar"
                alertDialog.dismiss()
            }
        }

        alertDialog.show()
    }

    // ==========================================
    // OBSERVACIÓN DEL ESTADO DEL TESTIGO
    // ==========================================

    private fun observeBatonState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    BatonManager.isCarryingBaton.collect { isCarrying ->
                        updateCarryingBatonUI(isCarrying)
                        handleNfcModeForBatonState(isCarrying)
                    }
                }

                launch {
                    BatonManager.currentBaton.collect { baton ->
                        updateBatonDetailsUI(baton)
                    }
                }

                launch {
                    BatonManager.handoffEvents.collect { event ->
                        handleHandoffEvent(event)
                    }
                }
            }
        }
    }

    private fun updateCarryingBatonUI(isCarrying: Boolean) {
        if (isCarrying) {
            // EN SPRINT CON EL TESTIGO (VERDE)
            cardBatonStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_sprint_bg))
            cardBatonStatus.strokeColor = ContextCompat.getColor(this, R.color.status_sprint_border)
            ivBatonStatusIcon.setImageResource(R.drawable.ic_sprint)
            ivBatonStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_sprint_primary))
            tvBatonStatusTitle.text = "PORTANDO EL TESTIGO (EN CARRERA)"
            tvBatonStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_sprint_text))
            tvBatonStatusDesc.text = "Tienes el testigo. ¡Corre hacia la zona de traspaso!"

            tvNfcTitle.text = "Listo para traspasar el testigo"
            tvNfcSubtitle.text = "HCE Activo • Acerca el teléfono al receptor para pasar"
            tvNfcSubtitle.setTextColor(ContextCompat.getColor(this, R.color.accent_green))

            stopPulseAnimation()
        } else {
            // ESPERANDO EL TESTIGO (ÁMBAR)
            cardBatonStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_wait_bg))
            cardBatonStatus.strokeColor = ContextCompat.getColor(this, R.color.status_wait_border)
            ivBatonStatusIcon.setImageResource(R.drawable.ic_nfc_tap)
            ivBatonStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_wait_primary))
            tvBatonStatusTitle.text = "ESPERANDO EL TESTIGO (LISTO PARA ESCANEAR)"
            tvBatonStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_wait_text))
            tvBatonStatusDesc.text = "Esperando al corredor entrante. Acerca los teléfonos dorso con dorso."

            tvNfcTitle.text = "Escaneando testigo entrante"
            tvNfcSubtitle.text = "Lector NFC Activo • En espera para el traspaso"
            tvNfcSubtitle.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))

            startPulseAnimation()
        }
    }

    private fun updateBatonDetailsUI(baton: BatonData?) {
        if (baton != null) {
            tvRaceId.text = baton.raceId
            tvTeamName.text = baton.teamId
            tvLegBadge.text = "ETAPA ${baton.legIndex}"
            tvRunnerName.text = baton.runnerName
            tvBatonDetails.text = "FIRMA: ${baton.signatureToken.ifEmpty { "AUTENTICADO" }} • Etapa ${baton.legIndex}"
        } else {
            tvRaceId.text = "--"
            tvTeamName.text = "--"
            tvLegBadge.text = "ETAPA --"
            tvRunnerName.text = "Sin Corredor"
            tvBatonDetails.text = "Sin Datos de Testigo"
        }
    }

    private fun handleNfcModeForBatonState(isCarrying: Boolean) {
        if (!isCarrying) {
            relayBatonReader.startScanning()
            isNfcReaderManualScanning = true
            btnToggleNfcScan.text = "Lector NFC: Activo (Toca para detener)"
            btnToggleNfcScan.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_amber)
            btnToggleNfcScan.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        } else {
            if (isNfcReaderManualScanning) {
                relayBatonReader.stopScanning()
                isNfcReaderManualScanning = false
            }
            btnToggleNfcScan.text = "Iniciar Lector NFC (Escaneo manual)"
            btnToggleNfcScan.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_cyan)
            btnToggleNfcScan.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        }
    }

    private fun toggleManualNfcScanning() {
        if (isNfcReaderManualScanning) {
            relayBatonReader.stopScanning()
            isNfcReaderManualScanning = false
            btnToggleNfcScan.text = "Iniciar Lector NFC (Escaneo manual)"
            btnToggleNfcScan.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_cyan)
            btnToggleNfcScan.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            tvNfcHandoffLog.text = "Lector NFC pausado manualmente"
        } else {
            relayBatonReader.startScanning()
            isNfcReaderManualScanning = true
            btnToggleNfcScan.text = "Lector NFC: Activo (Toca para detener)"
            btnToggleNfcScan.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_amber)
            btnToggleNfcScan.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            tvNfcHandoffLog.text = "Lector NFC escaneando etiquetas ISO-DEP cercanas..."
            startPulseAnimation()
        }
    }

    private fun handleHandoffEvent(event: HandoffEvent) {
        when (event) {
            is HandoffEvent.BatonSent -> {
                vibrateHandoffSent()
                tvNfcHandoffLog.text = "🎉 ¡Traspaso completado! Testigo transferido a la Etapa ${event.baton.legIndex + 1}"
                tvNfcHandoffLog.setTextColor(ContextCompat.getColor(this, R.color.status_sprint_text))
                pauseRaceTimer()
                tvTimerSubtext.text = "¡Etapa ${event.baton.legIndex} finalizada! Parcial guardado."
                Toast.makeText(this, "⚡ ¡Testigo traspasado con éxito!", Toast.LENGTH_SHORT).show()
            }
            is HandoffEvent.BatonReceived -> {
                vibrateBatonReceived()
                tvNfcHandoffLog.text = "🎉 ¡Testigo recibido! Etapa ${event.baton.legIndex} activa: ¡CORRE!"
                tvNfcHandoffLog.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                if (timerState != TimerState.RUNNING) {
                    startRaceTimer()
                }
                tvTimerSubtext.text = "¡Etapa ${event.baton.legIndex} en progreso! ¡Corre!"
                Toast.makeText(this, "🔥 ¡Testigo recibido! ¡Adelante!", Toast.LENGTH_SHORT).show()
            }
            is HandoffEvent.HandshakeError -> {
                vibrateError()
                tvNfcHandoffLog.text = "⚠️ Evento NFC: ${event.reason}"
                tvNfcHandoffLog.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            }
        }
    }

    private fun triggerManualBatonHandoff() {
        val current = BatonManager.currentBaton.value
        val isCarrying = BatonManager.isCarryingBaton.value

        if (isCarrying && current != null) {
            val nextLeg = current.legIndex + 1
            val updatedBaton = current.copy(
                legIndex = nextLeg,
                runnerName = "Corredor $nextLeg",
                timestampMs = System.currentTimeMillis(),
                signatureToken = "SIG-ETAPA$nextLeg-${System.currentTimeMillis() % 10000}"
            )
            BatonManager.onBatonTransferredOut("Activación manual por botón")
            BatonManager.updateBaton(updatedBaton, carrying = false)
            Toast.makeText(this, "¡Testigo pasado al Corredor $nextLeg!", Toast.LENGTH_SHORT).show()
        } else if (current != null) {
            val updatedBaton = current.copy(
                timestampMs = System.currentTimeMillis()
            )
            BatonManager.onBatonReceivedIn(updatedBaton)
            Toast.makeText(this, "¡Testigo recibido! Listo para correr.", Toast.LENGTH_SHORT).show()
        }
    }

    // ==========================================
    // CRONÓMETRO Y TEMPORIZADOR DE CARRERA
    // ==========================================

    private fun startRaceTimer() {
        timerState = TimerState.RUNNING
        timerStartTime = SystemClock.elapsedRealtime()
        tvTimerStatus.text = "EN CARRERA"
        tvTimerStatus.setTextColor(ContextCompat.getColor(this, R.color.status_sprint_primary))
        tvTimerSubtext.text = "Cronómetro activo • Transmitiendo telemetría"

        btnStartPause.text = "PAUSAR ETAPA"
        btnStartPause.setIconResource(R.drawable.ic_pause)
        btnStartPause.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_amber)

        gpsTracker.startTracking()

        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive && timerState == TimerState.RUNNING) {
                val currentElapsed = accumulatedTimeMs + (SystemClock.elapsedRealtime() - timerStartTime)
                updateTimerDisplay(currentElapsed)
                updateCadenceAndTelemetry()
                delay(40) // 25 FPS fluido
            }
        }
    }

    private fun pauseRaceTimer() {
        if (timerState != TimerState.RUNNING) return
        timerState = TimerState.PAUSED
        accumulatedTimeMs += SystemClock.elapsedRealtime() - timerStartTime
        timerJob?.cancel()

        gpsTracker.stopTracking()

        tvTimerStatus.text = "PAUSADO"
        tvTimerStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        tvTimerSubtext.text = "Cronómetro pausado"

        btnStartPause.text = "REANUDAR ETAPA"
        btnStartPause.setIconResource(R.drawable.ic_play)
        btnStartPause.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
    }

    private fun resetRace() {
        timerState = TimerState.STOPPED
        timerJob?.cancel()
        accumulatedTimeMs = 0L
        updateTimerDisplay(0L)

        totalSteps = 0
        initialStepCounterValue = -1
        accumulatedIndoorDistanceMeters = 0f
        smoothedCadence = 0f
        lastStepDetectedTime = 0L
        gpsTracker.reset()

        tvTimerStatus.text = "LISTO"
        tvTimerStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        tvTimerSubtext.text = "Presiona INICIAR para comenzar el cronometraje"

        btnStartPause.text = "INICIAR ETAPA"
        btnStartPause.setIconResource(R.drawable.ic_play)
        btnStartPause.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)

        updateMetricsUI(cadence = 0, distanceMeters = 0f, paceMinutesPerKm = 0f, steps = 0)
    }

    private fun updateTimerDisplay(elapsedMs: Long) {
        val minutes = (elapsedMs / 60000) % 60
        val seconds = (elapsedMs / 1000) % 60
        val centis = (elapsedMs % 1000) / 10
        tvTimerMain.text = String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centis)
    }

    // ==========================================
    // SENSORES Y TELEMETRÍA DINÁMICA
    // ==========================================

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                if (event.values.isNotEmpty() && event.values[0] == 1.0f) {
                    onStepDetected()
                }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val rawCount = event.values[0].toInt()
                if (initialStepCounterValue < 0) {
                    initialStepCounterValue = rawCount
                }
                val currentSteps = rawCount - initialStepCounterValue
                if (currentSteps > totalSteps) {
                    val stepDiff = currentSteps - totalSteps
                    for (i in 0 until stepDiff) {
                        onStepDetected()
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                lastAccMagnitude = magnitude

                if (!isCalibrating) {
                    filteredAccel = accelFilterAlpha * filteredAccel + (1 - accelFilterAlpha) * magnitude
                    val delta = magnitude - filteredAccel
                    val now = System.currentTimeMillis()

                    val triggerThreshold = maxOf(2.2f, sensorNoiseThreshold + 1.6f)
                    if (delta > triggerThreshold && (now - lastAccelerometerStepTime) > 240) {
                        lastAccelerometerStepTime = now
                        if (stepDetectorSensor == null) {
                            onStepDetected()
                        }
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Sin operación
    }

    private fun onStepDetected() {
        if (timerState != TimerState.RUNNING) return

        val now = System.currentTimeMillis()
        val dt = now - lastStepDetectedTime

        // Filtro anti-rebote espurio (< 230 ms = > 260 SPM no realista)
        if (dt < 230L && lastStepDetectedTime != 0L) return

        totalSteps++

        // Estimación de Cadencia (PPM) con suavizado EMA
        if (dt in 230L..2500L) {
            val instantSpm = (60_000f / dt).coerceIn(40f, 240f)
            smoothedCadence = if (smoothedCadence <= 5f) {
                instantSpm
            } else {
                0.60f * smoothedCadence + 0.40f * instantSpm
            }
        } else if (lastStepDetectedTime == 0L) {
            smoothedCadence = 120f
        }
        lastStepDetectedTime = now

        // Longitud de zancada dinámica según intensidad del trote/carrera y espacio
        val dynamicAccel = abs(lastAccMagnitude - baselineGravity)
        val stepStride = calculateDynamicStride(smoothedCadence, dynamicAccel)
        accumulatedIndoorDistanceMeters += stepStride

        updateCadenceAndTelemetry()
    }

    /**
     * Calcula la longitud de zancada dinámica (m) según cadencia e intensidad del movimiento.
     * Si se corre en un espacio pequeño o en el sitio, evita acumular 50m falsos.
     */
    private fun calculateDynamicStride(cadence: Float, dynamicAccel: Float): Float {
        return when {
            cadence < 95f -> {
                // Pasos lentos / desplazamiento mínimo en reposo
                0.28f + (dynamicAccel * 0.04f).coerceIn(0f, 0.12f)
            }
            cadence in 95f..130f -> {
                // Trote suave / trote en el sitio en espacios reducidos / caminata
                0.38f + (dynamicAccel * 0.06f).coerceIn(0f, 0.18f)
            }
            cadence in 130f..165f -> {
                // Trote en carrera activa
                0.55f + (dynamicAccel * 0.08f).coerceIn(0f, 0.22f)
            }
            else -> {
                // Sprint a máxima velocidad
                0.75f + (dynamicAccel * 0.10f).coerceIn(0f, 0.30f)
            }
        }.coerceIn(0.20f, 1.20f)
    }

    private fun updateCadenceAndTelemetry() {
        val now = System.currentTimeMillis()

        // Decaimiento suave si no hay nuevos pasos
        val timeSinceLastStep = now - lastStepDetectedTime
        if (lastStepDetectedTime > 0L) {
            if (timeSinceLastStep > 2400L) {
                smoothedCadence *= 0.70f
                if (smoothedCadence < 25f) smoothedCadence = 0f
            }
            if (timeSinceLastStep > 3500L) {
                smoothedCadence = 0f
            }
        } else {
            smoothedCadence = 0f
        }

        val currentCadenceInt = smoothedCadence.toInt()

        // Si hay fijación GPS con alta precisión exterior (>3m acumulados), usa GPS real; si no, usa zancada adaptativa interior
        val gpsData = gpsTracker.gpsFlow.value
        val finalDistance = if (gpsData.isGpsFixed && gpsData.totalDistanceMeters > 3.0f) {
            gpsData.totalDistanceMeters
        } else {
            accumulatedIndoorDistanceMeters
        }

        // Velocidad estimada en m/s
        val currentStride = calculateDynamicStride(smoothedCadence, abs(lastAccMagnitude - baselineGravity))
        val speedMps = if (gpsData.isGpsFixed && gpsData.speedMps > 0.4f) {
            gpsData.speedMps
        } else {
            (smoothedCadence / 60.0f) * currentStride
        }

        val paceMinPerKm = if (speedMps > 0.45f) {
            (1000f / speedMps) / 60f
        } else {
            0f
        }

        updateMetricsUI(currentCadenceInt, finalDistance, paceMinPerKm, totalSteps)
    }

    private fun updateMetricsUI(cadence: Int, distanceMeters: Float, paceMinutesPerKm: Float, steps: Int) {
        tvCadenceValue.text = cadence.toString()
        tvDistanceValue.text = String.format(Locale.US, "%.1f", distanceMeters)
        tvStepCountValue.text = steps.toString()

        if (paceMinutesPerKm > 0.5f && paceMinutesPerKm < 30f) {
            val paceMin = paceMinutesPerKm.toInt()
            val paceSec = ((paceMinutesPerKm - paceMin) * 60).toInt()
            tvPaceValue.text = String.format(Locale.US, "%d:%02d", paceMin, paceSec)
        } else {
            tvPaceValue.text = "--:--"
        }
    }

    // ==========================================
    // ANIMACIONES Y RESPUESTA HÁPTICA
    // ==========================================

    private fun startPulseAnimation() {
        if (viewNfcPulseRing.animation == null && pulseAnimation != null) {
            viewNfcPulseRing.startAnimation(pulseAnimation)
        }
    }

    private fun stopPulseAnimation() {
        viewNfcPulseRing.clearAnimation()
    }

    private fun performTactileClick() {
        vibrate(30, 80)
    }

    private fun vibrateBatonReceived() {
        vibratePattern(longArrayOf(0, 150, 80, 260), intArrayOf(0, 255, 0, 255))
    }

    private fun vibrateHandoffSent() {
        vibratePattern(longArrayOf(0, 100, 60, 100, 60, 180), intArrayOf(0, 200, 0, 220, 0, 255))
    }

    private fun vibrateError() {
        vibrate(250, 255)
    }

    private fun vibrate(durationMs: Long, amplitude: Int) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error de vibración: ${e.message}")
        }
    }

    private fun vibratePattern(timings: LongArray, amplitudes: IntArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error de patrón de vibración: ${e.message}")
        }
    }

    // ==========================================
    // CICLO DE VIDA
    // ==========================================

    override fun onResume() {
        super.onResume()
        registerSensors()
        if (!BatonManager.isCarryingBaton.value || isNfcReaderManualScanning) {
            relayBatonReader.startScanning()
            startPulseAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        relayBatonReader.stopScanning()
        stopPulseAnimation()
        unregisterSensors()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
    }
}