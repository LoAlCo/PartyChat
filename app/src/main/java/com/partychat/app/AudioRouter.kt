package com.partychat.app

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Puts the app in "call" audio mode and sends voice to the best headset available:
 * Bluetooth (LE or classic) > wired > USB > (speaker or earpiece fallback).
 *
 * The WebView's voice chat engine sometimes flips the route back on its own, so while
 * active this re-checks every few seconds and re-applies the choice if it drifted.
 */
class AudioRouter(context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false

    /**
     * Helper mode: used with the official Xbox app. Forces the Bluetooth headset into
     * "call" mode (SCO) system-wide, so other apps' voice audio and mic go through it,
     * and plays silence on the call channel so the phone doesn't drop the link.
     */
    var helperMode = false
    private var keepAlive: android.media.AudioTrack? = null
    @Volatile private var keepAliveRunning = false

    /** If no headset is connected: true = loudspeaker, false = earpiece. */
    var speakerWhenNoHeadset = true

    /** Called with a short description like "Bluetooth headset" whenever the route changes. */
    var onRouteChanged: ((String) -> Unit)? = null
    private var lastDescription = ""

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = scheduleRoute(600)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = scheduleRoute(600)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            route(force = false)
            handler.postDelayed(this, 15000)
        }
    }

    fun start() {
        if (active) return
        active = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.registerAudioDeviceCallback(deviceCallback, handler)
        route()
        if (helperMode) {
            forceScoOn()
            startKeepAlive()
        }
    }

    @Suppress("DEPRECATION")
    private fun forceScoOn() {
        try {
            val hasBt = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            if (hasBt) {
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
        } catch (e: Exception) { Log.w(TAG, "SCO start failed", e) }
    }

    private fun startKeepAlive() {
        if (keepAlive != null) return
        val rate = 16000
        val min = android.media.AudioTrack.getMinBufferSize(rate,
            android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(min * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) { Log.w(TAG, "keep-alive track failed", e); return }
        keepAlive = track
        keepAliveRunning = true
        track.play()
        Thread {
            val silence = ShortArray(rate / 50) // 20 ms of silence
            while (keepAliveRunning) {
                try { track.write(silence, 0, silence.size) } catch (_: Exception) { break }
            }
        }.apply { name = "PartyChat-keepalive"; isDaemon = true; start() }
    }

    private fun stopKeepAlive() {
        keepAliveRunning = false
        try { keepAlive?.stop() } catch (_: Exception) {}
        try { keepAlive?.release() } catch (_: Exception) {}
        keepAlive = null
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        stopKeepAlive()
        if (helperMode) {
            @Suppress("DEPRECATION")
            try { am.stopBluetoothSco(); am.isBluetoothScoOn = false } catch (_: Exception) {}
        }
        try { am.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {}
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun scheduleRoute(delayMs: Long) {
        if (!active) return
        handler.postDelayed({ route(); if (helperMode) forceScoOn() }, delayMs)
    }

    /** Re-apply the best route now. Safe to call any time. */
    fun route(force: Boolean = true) {
        if (!active) return
        try {
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) routeModern(force) else if (force) routeLegacy()
        } catch (e: Exception) {
            Log.w(TAG, "route failed", e)
        }
    }

    private fun headsetRank(type: Int): Int = when (type) {
        26 /* TYPE_BLE_HEADSET */ -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        AudioDeviceInfo.TYPE_USB_HEADSET -> 3
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 4
        else -> -1
    }

    private fun describe(type: Int): String = when (type) {
        26, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
        else -> "Other device"
    }

    private fun routeModern(force: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val devices = am.availableCommunicationDevices
        val headset = devices
            .filter { headsetRank(it.type) >= 0 }
            .minByOrNull { headsetRank(it.type) }
        val fallbackType = if (speakerWhenNoHeadset) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                           else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val target = headset ?: devices.firstOrNull { it.type == fallbackType } ?: return

        val current = am.communicationDevice
        // Background checks only fix a real drift (e.g. stuck on speaker while a headset
        // is connected). Re-applying the same route restarts the audio path = choppy voice.
        val needsChange = current == null || current.type != target.type
        if (needsChange) {
            am.setCommunicationDevice(target)
        }
        report(describe(target.type))
    }

    @Suppress("DEPRECATION")
    private fun routeLegacy() {
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBt = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val wired = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        when {
            hasBt -> {
                am.isSpeakerphoneOn = false
                if (!am.isBluetoothScoOn) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                report("Bluetooth headset")
            }
            wired != null -> {
                if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
                am.isSpeakerphoneOn = false
                report(describe(wired.type))
            }
            else -> {
                if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
                am.isSpeakerphoneOn = speakerWhenNoHeadset
                report(if (speakerWhenNoHeadset) "Phone speaker" else "Phone earpiece")
            }
        }
    }

    private fun report(description: String) {
        if (description != lastDescription) {
            lastDescription = description
            onRouteChanged?.invoke(description)
        }
    }

    companion object { private const val TAG = "AudioRouter" }
}
