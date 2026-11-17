package app.willbolt.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.willbolt.core.AdultList
import app.willbolt.core.RuleEngine
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import app.willbolt.util.Notifications
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A DNS-only VPN. It claims just the (fake) DNS server address, so ONLY name lookups pass through the app;
 * all other traffic goes straight out the normal way and nothing is sent to any VPN server.
 *
 * Each lookup is answered locally (blocked name -> "does not exist", search engines -> their safe-search
 * front end) or passed on to the network's real DNS server.
 *
 * Safety: because every lookup on the phone depends on this code, it tests itself end to end (a real lookup of
 * a made-up name through the real tunnel). If that ever stops working it switches itself OFF so the internet keeps working.
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "DnsVpn"
        private const val NOTIF_ID = 1002
        const val ACTION_STOP = "app.willbolt.vpn.STOP"

        private const val VPN_ADDR = "10.255.255.1"
        private const val DNS_ADDR = "10.255.255.2"
        private val SELFTEST_ANSWER = byteArrayOf(10, -1, -1, 1) // 10.255.255.1

        /** Popular resolvers that apps hard-code. Routing them here makes those lookups go through the filter too. */
        private val HARDCODED_DNS = listOf(
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112", "208.67.222.222", "208.67.220.220",
        )

        @Volatile
        var running = false
            private set

        /** True once a real lookup through the tunnel has been seen to work. */
        @Volatile
        var verified = false
            private set

        /** Android's Private DNS points at a fixed server, so apps skip this filter entirely. */
        @Volatile
        var bypassedByPrivateDns = false
            private set

        @Volatile
        private var brokenUntil = 0L

        /** The filter switched itself off after failing its self-test and is waiting before trying again. */
        fun pausedAfterFailure(): Boolean = SystemClock.elapsedRealtime() < brokenUntil

        fun start(ctx: Context, force: Boolean = false) {
            if (force) brokenUntil = 0L
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DnsVpnService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "could not start: $t")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
            } catch (t: Throwable) {
                Log.w(TAG, "could not stop: $t")
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var reader: Thread? = null
    private val writeLock = Any()
    private val pool: ExecutorService = Executors.newFixedThreadPool(6)
    private val lookupExec: ExecutorService = Executors.newCachedThreadPool()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var ipId = 0

    private val resolveCache = ConcurrentHashMap<String, Pair<Long, List<ByteArray>>>()

    @Volatile
    private var upstreamCache: Pair<Long, List<InetAddress>>? = null

    @Volatile
    private var upstreamNetwork: Network? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        ConfigStore.init(this)
        EventLog.init(this)
        RuleEngine.init(this)
        AdultList.preload(this)
        Notifications.ensureChannels(this)
        goForeground()
        if (pausedAfterFailure()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) establish()
        return START_STICKY
    }

    private fun goForeground() {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, Notifications.foreground(this, "Website filter is running"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun establish() {
        val b = Builder()
            .setSession("Willbolt")
            .setMtu(1500)
            .addAddress(VPN_ADDR, 24)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32)
            .setBlocking(true)
        for (ip in HARDCODED_DNS) b.addRoute(ip, 32)
        val pfd = try {
            b.establish()
        } catch (t: Throwable) {
            Log.w(TAG, "establish failed", t)
            null
        }
        if (pfd == null) {
            Log.w(TAG, "VPN not approved yet - nothing to do")
            stopSelf()
            return
        }
        tun = pfd
        tunOut = FileOutputStream(pfd.fileDescriptor)
        verified = false
        bypassedByPrivateDns = false
        running = true
        reader = Thread({ readLoop(pfd) }, "willbolt-dns").also { it.start() }
        EventLog.add("VPN", "Website filter started")
        timer.schedule({ runSelfTest(1) }, 2, TimeUnit.SECONDS)
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    try {
                        handlePacket(buf, n)
                    } catch (t: Throwable) {
                        Log.w(TAG, "bad packet ignored", t) // one odd packet must never stop the filter
                    }
                }
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "tunnel read ended: $e")
        } finally {
            if (running && tun === pfd) {
                // The reader died while we still thought we were up: tear the tunnel down so DNS goes back to normal.
                EventLog.add("VPN", "Website filter stopped unexpectedly")
                shutdown()
                stopSelf()
            }
        }
    }

    // ---- packets from apps ---------------------------------------------------------------------------------

    private fun handlePacket(b: ByteArray, n: Int) {
        if (n < 28) return
        if (((b[0].toInt() ushr 4) and 0xF) != 4) return // IPv4 only
        val ihl = (b[0].toInt() and 0xF) * 4
        if (ihl < 20 || n < ihl + 8) return
        when (b[9].toInt() and 0xFF) {
            17 -> { // UDP
                if (DnsPacket.u16(b, ihl + 2) != 53) return
                val udpLen = DnsPacket.u16(b, ihl + 4)
                val len = minOf(udpLen - 8, n - ihl - 8)
                if (len < 17) return
                val query = b.copyOfRange(ihl + 8, ihl + 8 + len)
                val clientIp = b.copyOfRange(12, 16)
                val serverIp = b.copyOfRange(16, 20)
                val clientPort = DnsPacket.u16(b, ihl)
                try {
                    pool.execute { answer(query, clientIp, serverIp, clientPort) }
                } catch (_: Throwable) {
                }
            }
            // DoT / DoH attempts to the addresses we own: refuse at once so apps fall back to plain DNS
            6 -> Packets.tcpResetFor(b, n, ihl, nextId())?.let { write(it) }
        }
    }

    private fun answer(query: ByteArray, clientIp: ByteArray, serverIp: ByteArray, clientPort: Int) {
        try {
            val q = DnsPacket.parseQuestion(query)
            val response: ByteArray? = if (q == null) {
                forward(query)
            } else {
                when (val d = DnsPolicy.decide(ConfigStore.value, q.name)) {
                    is DnsPolicy.Decision.Block -> {
                        EventLog.add("SITE_BLOCK", "${q.name} - ${d.reason}", dedupeMs = 60_000)
                        DnsPacket.nxdomain(query, q)
                    }
                    is DnsPolicy.Decision.Rewrite -> rewrite(query, q, d.target) ?: forward(query)
                    DnsPolicy.Decision.SelfTest ->
                        if (q.type == 1) DnsPacket.answer(query, q, 1, 1, listOf(SELFTEST_ANSWER)) else DnsPacket.noData(query, q)
                    DnsPolicy.Decision.Forward -> forward(query)
                }
            }
            if (response != null) {
                val fitted = if (response.size > 1400 && q != null) DnsPacket.truncated(query, q) else response
                write(Packets.udpReply(serverIp, clientIp, 53, clientPort, nextId(), fitted))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dns query failed", t)
        }
    }

    // ---- answering ---------------------------------------------------------------------------------------------------

    private fun rewrite(query: ByteArray, q: DnsPacket.Question, target: String): ByteArray? {
        val addrs = resolveTarget(target) ?: return null
        val v4 = addrs.filter { it.size == 4 }
        val v6 = addrs.filter { it.size == 16 }
        return when (q.type) {
            1 -> if (v4.isEmpty()) DnsPacket.noData(query, q) else DnsPacket.answer(query, q, 1, 300, v4)
            28 -> if (v6.isEmpty()) DnsPacket.noData(query, q) else DnsPacket.answer(query, q, 28, 300, v6)
            else -> DnsPacket.noData(query, q) // HTTPS/SVCB records could carry real IP hints - say there are none
        }
    }

    /** Looks the safe-search front end up through our own upstream (never through the tunnel, so no loops). */
    private fun resolveTarget(host: String): List<ByteArray>? {
        val now = SystemClock.elapsedRealtime()
        resolveCache[host]?.let { if (now - it.first < 300_000) return it.second }
        val a = forward(DnsPacket.query(host, 1, nextId()))?.let { DnsPacket.parseAddresses(it) } ?: emptyList()
        val aaaa = forward(DnsPacket.query(host, 28, nextId()))?.let { DnsPacket.parseAddresses(it) } ?: emptyList()
        val all = a.filter { it.size == 4 } + aaaa.filter { it.size == 16 }
        if (all.isEmpty()) return null
        resolveCache[host] = now to all
        return all
    }

    private fun forward(query: ByteArray): ByteArray? {
        for (server in upstreamServers()) {
            try {
                DatagramSocket().use { sock ->
                    protect(sock) // this socket must bypass the VPN, or it would loop back into our own tunnel
                    upstreamNetwork?.let {
                        try {
                            it.bindSocket(sock)
                        } catch (_: Throwable) {
                        }
                    }
                    sock.soTimeout = 2500
                    sock.send(DatagramPacket(query, query.size, server, 53))
                    val buf = ByteArray(4096)
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    return buf.copyOf(pkt.length)
                }
            } catch (_: SocketTimeoutException) {
                // try the next server
            } catch (_: IOException) {
            }
        }
        return null
    }

    private fun findNetwork(cm: ConnectivityManager): Network? {
        fun usable(n: Network): Boolean {
            val c = cm.getNetworkCapabilities(n) ?: return false
            return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        cm.activeNetwork?.let { if (usable(it)) return it }
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { usable(it) }
    }

    private fun upstreamServers(): List<InetAddress> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val custom = ConfigStore.value.upstreamDns.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (custom.isNotEmpty()) {
            val parsed = custom.mapNotNull {
                try {
                    InetAddress.getByName(it)
                } catch (_: Throwable) {
                    null
                }
            }
            if (parsed.isNotEmpty()) {
                upstreamNetwork = findNetwork(cm)
                return parsed
            }
        }
        val now = SystemClock.elapsedRealtime()
        upstreamCache?.let { if (now - it.first < 10_000) return it.second }
        val net = findNetwork(cm)
        upstreamNetwork = net
        val list = ArrayList<InetAddress>()
        net?.let { n -> cm.getLinkProperties(n)?.dnsServers?.let { list.addAll(it) } }
        if (list.isEmpty()) {
            list.add(InetAddress.getByName("1.1.1.1"))
            list.add(InetAddress.getByName("9.9.9.9"))
        }
        upstreamCache = now to list
        return list
    }

    private fun write(packet: ByteArray) {
        synchronized(writeLock) {
            try {
                tunOut?.write(packet)
            } catch (_: IOException) {
            }
        }
    }

    @Synchronized
    private fun nextId(): Int {
        ipId = (ipId + 1) and 0xFFFF
        return ipId
    }

    // ---- self-test and fail-open -----------------------------------------------------------------------------------------

    private fun privateDnsIsFixed(): Boolean = try {
        Settings.Global.getString(contentResolver, "private_dns_mode") == "hostname"
    } catch (_: Throwable) {
        false
    }

    /** A real lookup of a made-up name, from this process, through the real tunnel. */
    private fun lookupSentinel(): Boolean {
        val name = DnsPolicy.SELFTEST_PREFIX + (100_000..999_999).random() + ".example.com"
        val task = lookupExec.submit(
            Callable {
                try {
                    InetAddress.getByName(name).address.contentEquals(SELFTEST_ANSWER)
                } catch (_: Throwable) {
                    false
                }
            },
        )
        return try {
            task.get(4, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            task.cancel(true)
            false
        }
    }

    private fun runSelfTest(attempt: Int) {
        if (!running) return
        try {
            val ok = lookupSentinel()
            if (!running) return
            if (ok) {
                if (!verified) EventLog.add("VPN", "Self-test passed - lookups flow through the filter")
                verified = true
                bypassedByPrivateDns = false
                timer.schedule({ runSelfTest(1) }, 5, TimeUnit.MINUTES)
                return
            }
            verified = false
            if (privateDnsIsFixed()) {
                // Not a bug in the filter: Android sends lookups to a fixed server, around the VPN.
                if (!bypassedByPrivateDns) {
                    EventLog.add("VPN", "Private DNS is set to a fixed server - apps skip the website filter. Set Private DNS to Automatic.")
                }
                bypassedByPrivateDns = true
                timer.schedule({ runSelfTest(1) }, 60, TimeUnit.SECONDS)
                return
            }
            if (attempt < 3) {
                timer.schedule({ runSelfTest(attempt + 1) }, 4, TimeUnit.SECONDS)
            } else {
                failOpen("lookups through the filter stopped working")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "self-test error", t)
        }
    }

    /** Better an unfiltered internet than none at all. */
    private fun failOpen(reason: String) {
        brokenUntil = SystemClock.elapsedRealtime() + 10 * 60_000L
        EventLog.add("VPN", "Website filter switched itself off: $reason")
        shutdown()
        stopSelf()
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------------------

    private fun shutdown() {
        val wasRunning = running
        running = false
        verified = false
        bypassedByPrivateDns = false
        try {
            tun?.close()
        } catch (_: Throwable) {
        }
        tun = null
        tunOut = null
        reader?.interrupt()
        reader = null
        if (wasRunning) EventLog.add("VPN", "Website filter stopped")
    }

    /** Another VPN app took over, or the user switched this one off. */
    override fun onRevoke() {
        EventLog.add("VPN", "VPN permission was taken away (another VPN app, or switched off)")
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        timer.shutdownNow()
        lookupExec.shutdownNow()
        pool.shutdownNow()
        super.onDestroy()
    }
}
