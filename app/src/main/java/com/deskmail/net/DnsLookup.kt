package com.deskmail.net

import android.net.DnsResolver
import com.deskmail.core.provider.DnsAnswer
import com.deskmail.core.provider.ServiceHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Asks the device's resolver for the records Java's own lookup does not hand out: which hosts
 * take a domain's mail (MX) and which host offers a service (SRV). The answers are read by
 * [DnsAnswer]. The calls wait for the answer: not for the main thread.
 */
object DnsLookup {
    private const val TIMEOUT_MS = 5_000L

    /** The resolver answers on this executor: on the thread that brings the answer. */
    private val direct = Executor { it.run() }

    fun mailHosts(domain: String): List<String> = query(domain, DnsAnswer.TYPE_MX)?.let { DnsAnswer.mailHosts(it) }.orEmpty()

    fun services(name: String): List<ServiceHost> = query(name, DnsAnswer.TYPE_SRV)?.let { DnsAnswer.services(it) }.orEmpty()

    /** The raw answer, or null when there is none in time (no such name, no network). */
    private fun query(name: String, type: Int): ByteArray? {
        val done = CountDownLatch(1)
        var answer: ByteArray? = null
        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(bytes: ByteArray, rcode: Int) {
                if (rcode == 0) answer = bytes
                done.countDown()
            }

            override fun onError(error: DnsResolver.DnsException) {
                done.countDown()
            }
        }
        DnsResolver.getInstance().rawQuery(null, name, DnsResolver.CLASS_IN, type, DnsResolver.FLAG_EMPTY, direct, null, callback)
        done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return answer
    }
}
