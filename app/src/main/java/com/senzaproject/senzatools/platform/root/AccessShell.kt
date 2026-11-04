package com.senzaproject.senzatools.platform.root

import com.senzaproject.senzatools.platform.AccessManager
import com.senzaproject.senzatools.utils.StreamResult
import com.senzaproject.senzatools.utils.Sys

/** Adaptor [SystemShell] di atas [AccessManager] untuk aplikasi sungguhan. */
class AccessShell(private val access: AccessManager) : SystemShell {
    override val rootVerified: Boolean get() = access.isRootVerified
    override fun readFile(path: String): String? = Sys.readText(path)
    override fun app(command: String, timeoutMs: Long): StreamResult = Sys.stream(listOf("sh", "-c", command), timeoutMs)
    override fun root(command: String, label: String, timeoutMs: Long, tail: Int, keep: (String) -> Boolean): StreamResult =
        access.runRootStream(command, label, timeoutMs, tail, keep)
}
