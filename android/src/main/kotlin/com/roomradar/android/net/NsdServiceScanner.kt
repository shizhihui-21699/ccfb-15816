package com.roomradar.android.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.roomradar.core.model.ServiceFingerprint
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class NsdFoundService(
    val ip: String?,
    val port: Int,
    val fingerprint: ServiceFingerprint,
)

/**
 * mDNS/DNS-SD discovery. NSD is one of the most important Android paths because it does not
 * require reading /proc/net/arp and can identify camera, Cast, AirPlay and speaker services.
 *
 * Resolve requests are serialized: many Android NSD stacks reject overlapping resolve calls.
 */
@SuppressLint("MissingPermission")
class NsdServiceScanner(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val multicastLock = wifi?.createMulticastLock("roomradar:nsd")?.apply {
        setReferenceCounted(false)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    @Volatile
    private var active = false

    @Volatile
    private var foundCallback: ((NsdFoundService) -> Unit)? = null

    fun start(
        serviceTypes: List<String> = DEFAULT_SERVICE_TYPES,
        onFound: (NsdFoundService) -> Unit,
    ) {
        foundCallback = onFound
        active = true
        runCatching { multicastLock?.acquire() }

        serviceTypes.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    enqueueResolve(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            }
            synchronized(listeners) { listeners += listener }
            mainHandler.post {
                runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            }
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        if (!active) return
        resolveQueue += serviceInfo
        mainHandler.post(::processNextResolve)
    }

    private fun processNextResolve() {
        if (!active) return
        if (!resolving.compareAndSet(false, true)) return

        val serviceInfo = resolveQueue.poll()
        if (serviceInfo == null) {
            resolving.set(false)
            return
        }

        val dispatched = runCatching {
            nsd.resolveService(serviceInfo, resolveListener)
        }.isSuccess

        if (!dispatched) {
            resolving.set(false)
            mainHandler.post(::processNextResolve)
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            finishCurrentResolve()
        }

        override fun onServiceResolved(resolved: NsdServiceInfo) {
            if (active) {
                val rawAttributes: Map<String, ByteArray>? = resolved.attributes
                val attributes = rawAttributes.orEmpty().mapValues { (_, value) ->
                    value?.toString(Charsets.UTF_8).orEmpty()
                }
                val requestType = serviceTypeFor(resolved)
                val fingerprint = ServiceFingerprint(
                    type = requestType,
                    name = resolved.serviceName,
                    manufacturer = attributes.firstNonBlank(
                        "manufacturer", "mf", "mfgr", "vendor",
                    ),
                    model = attributes.firstNonBlank("model", "md", "modelName"),
                    endpoint = attributes.firstNonBlank("path", "rtsp", "x-addrs"),
                )
                foundCallback?.invoke(
                    NsdFoundService(
                        ip = @Suppress("DEPRECATION") resolved.host?.hostAddress,
                        port = resolved.port,
                        fingerprint = fingerprint,
                    ),
                )
            }
            finishCurrentResolve()
        }
    }

    private fun serviceTypeFor(resolved: NsdServiceInfo): String =
        resolved.serviceType?.takeIf { it.isNotBlank() } ?: "_unknown._tcp"

    private fun finishCurrentResolve() {
        resolving.set(false)
        if (active) mainHandler.post(::processNextResolve)
    }

    fun stop() {
        active = false
        foundCallback = null
        resolveQueue.clear()
        synchronized(listeners) {
            listeners.forEach { listener ->
                mainHandler.post {
                    runCatching { nsd.stopServiceDiscovery(listener) }
                }
            }
            listeners.clear()
        }
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }

    private fun Map<String, String>.firstNonBlank(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.takeIf { it.isNotBlank() } }

    companion object {
        val DEFAULT_SERVICE_TYPES = listOf(
            "_axis-video._tcp",
            "_onvif._tcp",
            "_rtsp._tcp",
            "_camera._tcp",
            "_http._tcp",
            "_googlecast._tcp",
            "_airplay._tcp",
            "_raop._tcp",
            "_androidtvremote._tcp",
            "_spotify-connect._tcp",
            "_sonos._tcp",
            "_hap._tcp",
            "_companion-link._tcp",
        )
    }
}
