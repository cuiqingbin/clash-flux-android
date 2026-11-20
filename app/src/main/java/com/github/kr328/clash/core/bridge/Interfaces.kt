package com.github.kr328.clash.core.bridge

interface TunInterface {
    fun markSocket(fd: Int)
    fun querySocketUid(protocol: Int, source: String, target: String): Int
}

interface LogcatInterface {
    fun received(message: String)
}

interface FetchCallback {
    fun complete(error: String)
    fun report(message: String)
}
