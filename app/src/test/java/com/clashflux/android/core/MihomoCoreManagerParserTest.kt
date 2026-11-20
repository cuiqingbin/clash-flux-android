package com.clashflux.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MihomoCoreManagerParserTest {

    @Test
    fun parseInetSocketAddress_handlesIpv4BracketedIpv6AndRejectsInvalidInputs() {
        val v4 = MihomoCoreManager.parseInetSocketAddress("127.0.0.1:7890")
        assertNotNull(v4)
        assertEquals("127.0.0.1", v4!!.address.hostAddress)
        assertEquals(7890, v4.port)

        val v6 = MihomoCoreManager.parseInetSocketAddress(" [::1]:443 ")
        assertNotNull(v6)
        assertEquals(443, v6!!.port)

        assertNull(MihomoCoreManager.parseInetSocketAddress(""))
        assertNull(MihomoCoreManager.parseInetSocketAddress("127.0.0.1"))
        assertNull(MihomoCoreManager.parseInetSocketAddress("[::1:443"))
        assertNull(MihomoCoreManager.parseInetSocketAddress("127.0.0.1:99999"))
        assertNull(MihomoCoreManager.parseInetSocketAddress("127.0.0.1:-1"))
        assertNull(MihomoCoreManager.parseInetSocketAddress(":8080"))
    }

    @Test
    fun parseLogMessage_parsesJsonAndBracketedLevelsAndFiltersHealthNoise() {
        val jsonEntry = MihomoCoreManager.parseLogMessage(
            """{"type":"warning","payload":"  TCP fallback enabled  ","time":"12:34:56"}"""
        )
        assertNotNull(jsonEntry)
        assertEquals("WARN", jsonEntry!!.type)
        assertEquals("TCP fallback enabled", jsonEntry.payload)
        assertEquals("12:34:56", jsonEntry.timeText)

        val errEntry = MihomoCoreManager.parseLogMessage("[err] handshake failed")
        assertNotNull(errEntry)
        assertEquals("ERROR", errEntry!!.type)
        assertEquals("handshake failed", errEntry.payload)

        val debugEntry = MihomoCoreManager.parseLogMessage("[debug]: dialer created")
        assertNotNull(debugEntry)
        assertEquals("DEBUG", debugEntry!!.type)
        assertEquals("dialer created", debugEntry.payload)

        val plainEntry = MihomoCoreManager.parseLogMessage("Plain info message")
        assertNotNull(plainEntry)
        assertEquals("INFO", plainEntry!!.type)
        assertEquals("Plain info message", plainEntry.payload)

        assertNull(MihomoCoreManager.parseLogMessage("   "))
        assertNull(MihomoCoreManager.parseLogMessage("""{"level":"info","payload":"Skip once health check"}"""))
        assertNull(MihomoCoreManager.parseLogMessage("[info] Skip once health check on group"))
    }
}
