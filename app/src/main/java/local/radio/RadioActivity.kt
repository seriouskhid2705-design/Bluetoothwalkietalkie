package local.radio

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.view.MotionEvent
import android.bluetooth.*
import android.app.AlertDialog
import android.os.Build
import android.provider.Settings
import android.widget.*
import java.io.*
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class RadioActivity : Activity() {
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var talk: Button
    private val writer = Executors.newSingleThreadExecutor()
    private var generation = 0
    @Volatile private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null
    private var output: DataOutputStream? = null
    private var host = false
    private var held = false
    private var owner = 0 // 0 idle, 1 host, 2 guest
    @Volatile private var captureId = 0
    private val me get() = if (host) 1 else 2

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 60, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(panel) })
        panel.addView(TextView(this).apply { text = "Bluetooth Radio"; textSize = 30f })
        status = TextView(this).apply { text = "Pair both phones in Bluetooth settings, then host or join a channel."; textSize = 18f }
        panel.addView(status)
        button("Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button("Host channel") { withBluetooth { createRoom(it) } }
        button("Join paired phone") { withBluetooth { choosePeer(it) } }
        talk = Button(this).apply {
            text = "Hold to talk"
            isEnabled = false
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> press()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
                }
                true
            }
        }
        panel.addView(talk)
        button("Leave room") { reset("Room closed.") }
    }
    private fun button(label: String, action: () -> Unit) {
        panel.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }
    private val service = UUID.fromString("30ef7c7b-b842-4873-a721-fb4710d1dd81")
    private var pending: ((BluetoothAdapter) -> Unit)? = null

    private fun withBluetooth(action: (BluetoothAdapter) -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pending = action
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> status.text = "This phone has no Bluetooth adapter."
            !adapter.isEnabled -> status.text = "Turn Bluetooth on in settings, then try again."
            else -> action(adapter)
        }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == 10) {
            val action = pending
            pending = null
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED && action != null) withBluetooth(action)
            else status.text = "Allow Nearby devices to connect the phones."
        } else if (request == 9) {
            status.text = if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) "Hold to talk when ready."
                else "Microphone access is needed to transmit."
        }
    }
    private fun reset(message: String) {
        generation++
        captureId++
        held = false
        owner = 0
        runCatching { server?.close() }
        runCatching { socket?.close() }
        server = null
        socket = null
        output = null
        talk.isEnabled = false
        status.text = message
    }
    private fun report(id: Int, message: String) = runOnUiThread {
        if (generation == id) reset(message)
    }
    private fun createRoom(adapter: BluetoothAdapter) {
        reset("Waiting for the paired phone to join…")
        host = true
        val id = generation
        try {
            val listener = adapter.listenUsingRfcommWithServiceRecord("Bluetooth Radio", service)
            server = listener
            thread {
                try {
                    val client = listener.accept()
                    listener.close()
                    session(id, client)
                } catch (e: Exception) { report(id, "Bluetooth connection closed.") }
            }
        } catch (e: Exception) { report(id, "Could not host. Check Bluetooth settings.") }
    }

    private fun choosePeer(adapter: BluetoothAdapter) {
        val devices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        if (devices.isEmpty()) {
            status.text = "Pair the two phones in Bluetooth settings first."
            return
        }
        val labels = devices.map { "${it.name ?: "Paired device"} · ${it.address.takeLast(5)}" }
        AlertDialog.Builder(this).setTitle("Choose the hosting phone")
            .setItems(labels.toTypedArray()) { _, index -> join(devices[index]) }.show()
    }

    private fun join(peer: BluetoothDevice) {
        reset("Connecting to the hosting phone…")
        host = false
        val id = generation
        try {
            val client = peer.createRfcommSocketToServiceRecord(service)
            socket = client
            thread {
                try {
                    client.connect()
                    session(id, client)
                } catch (e: Exception) { report(id, "Could not connect. Tap Host channel on the other phone first.") }
            }
        } catch (e: Exception) { report(id, "Could not open Bluetooth connection.") }
    }
    private fun session(id: Int, client: BluetoothSocket) {
        var player: AudioTrack? = null
        try {
            val input = DataInputStream(client.inputStream)
            val stream = DataOutputStream(client.outputStream)
            runOnUiThread {
                if (generation != id) { runCatching { client.close() }; return@runOnUiThread }
                socket = client
                output = stream
                talk.isEnabled = true
                status.text = "Connected · hold to talk"
            }
            while (true) {
                val type = input.readUnsignedByte()
                if (type == 3) {
                    val n = input.readUnsignedShort()
                    require(n in 2..1280 && n % 2 == 0)
                    val audio = ByteArray(n)
                    input.readFully(audio)
                    if (player == null) {
                        val min = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        player = AudioTrack(AudioManager.STREAM_MUSIC, 16000, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 2560), AudioTrack.MODE_STREAM)
                        player.play()
                    }
                    player.write(audio, 0, n)
                } else {
                    val value = if (type == 2) input.readUnsignedByte() else 0
                    runOnUiThread {
                        if (generation == id) when {
                            host && type == 0 && owner == 0 -> grant(2)
                            host && type == 1 && owner == 2 -> grant(0)
                            !host && type == 2 && value in 0..2 -> updateOwner(value)
                        }
                    }
                }
            }
        } catch (e: Exception) { /* EOF, network loss or audio device failure */ }
        finally {
            player?.release()
            runCatching { client.close() }
            report(id, "Disconnected. Create or join a room to reconnect.")
        }
    }
    private fun send(type: Int, value: Int = 0, bytes: ByteArray? = null) {
        val stream = output ?: return
        val id = generation
        writer.execute {
            try {
                stream.writeByte(type)
                if (type == 2) stream.writeByte(value)
                if (bytes != null) { stream.writeShort(bytes.size); stream.write(bytes) }
            } catch (e: IOException) { report(id, "Connection lost.") }
        }
    }
    private fun grant(value: Int) { send(2, value); updateOwner(value) }
    private fun updateOwner(value: Int) {
        owner = value
        status.text = when (value) {
            0 -> "Connected · hold to talk"
            me -> "You’re speaking"
            else -> "Listening · channel busy"
        }
        if (value == me && held) record()
        else if (value == me && !host) send(1)
    }
    private fun press() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 9)
            return
        }
        if (owner != 0 || held) return
        held = true
        if (host) grant(1) else send(0)
    }
    private fun release() {
        if (!held) return
        held = false
        captureId++
        if (!host) send(1) else if (owner == 1) grant(0)
    }
    private fun record() {
        val ticket = ++captureId
        val id = generation
        thread {
            var mic: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                mic = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 2560))
                mic.startRecording()
                val frame = ByteArray(640)
                while (ticket == captureId) {
                    val n = mic.read(frame, 0, frame.size)
                    require(n > 0)
                    val samples = frame.copyOf(n)
                    runOnUiThread { if (ticket == captureId && id == generation) send(3, bytes = samples) }
                }
            } catch (e: Exception) {
                runOnUiThread { if (ticket == captureId) { release(); status.text = "Microphone unavailable. Try again." } }
            } finally { mic?.release() }
        }
    }
    override fun onPause() { release(); super.onPause() }
    override fun onDestroy() { reset("Closed"); writer.shutdown(); super.onDestroy() }
}
