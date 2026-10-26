package com.sameerasw.airsync.utils

fun main() {
    val a = "a".repeat(32)
    val b = "b".repeat(32)
    val mac = RelayProtocol("mac", a)
    val android = RelayProtocol("android", b)
    check(mac.wrap("not ready") == null)
    check(mac.hello == "AB1|hello|mac|$a")
    check(mac.receive(android.hello).reply == mac.hello)
    check(android.receive(mac.hello).reply == android.hello)
    check(mac.receive(android.hello).reply == null)
    val payload = "{\"type\":\"clipboardUpdate\",\"data\":{\"text\":\"caffè|☕\"}}"
    val frame = mac.wrap(payload)!!
    check(frame == "AB1|data|mac|$a|$b|1|$payload")
    check(android.receive(frame).payload == payload)
    check(android.receive(frame).payload == null)
    android.receive(mac.hello)
    check(android.receive(frame).payload == null)
    check(mac.receive(frame).payload == null)
    val second = mac.wrap("two")!!
    val third = mac.wrap("three")!!
    check(android.receive(third).payload == "three")
    check(android.receive(second).payload == null)
    val fresh = RelayProtocol("android", "c".repeat(32))
    fresh.receive(mac.hello)
    check(fresh.receive(frame).payload == null)
    check(fresh.receive(payload).payload == null)
    check(fresh.receive("AB1|hello|mac|bad").reply == null)
    check(mac.receive(android.wrap("reply")!!).payload == "reply")

    check(RelayUrlPolicy.normalize("relay.example.com") == "wss://relay.example.com/ws")
    check(RelayUrlPolicy.normalize("ws://10.relay.example.com") == "wss://10.relay.example.com/ws")
    check(RelayUrlPolicy.normalize("ws://192.168.relay.example.com") == "wss://192.168.relay.example.com/ws")
    check(RelayUrlPolicy.normalize("ws://10.0.0.2:8080") == "ws://10.0.0.2:8080/ws")
    check(RelayUrlPolicy.normalize("ws://172.31.1.2") == "ws://172.31.1.2/ws")
    check(RelayUrlPolicy.normalize("ws://172.32.1.2") == "wss://172.32.1.2/ws")
    check(!RelayUrlPolicy.isPrivateHost("10.0.0.999"))
    check(!RelayUrlPolicy.isPrivateHost("010.0.0.1"))
    for (url in listOf("ws://user@relay.example.com", "https://relay.example.com", "wss://relay.example.com/ws?key=x", "wss://relay.example.com:0")) {
        check(runCatching { RelayUrlPolicy.normalize(url) }.isFailure)
    }
    println("Kotlin AirBridge security checks passed")
}
