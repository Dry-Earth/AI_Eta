package io.github.mangi.eta.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalEndpointPolicyTest {
    @Test fun plaintextHttpIsAllowedOnlyForLocalNetworks() {
        val allowed = listOf(
            "http://127.0.0.1:8000",
            "http://localhost:8000",
            "http://192.168.1.10:8000",
            "http://10.0.0.5",
            "http://172.16.3.4",
            "http://169.254.1.2",
            "http://100.64.0.1",
            "http://[::1]:8000",
            "http://nas.local:9000",
            "http://box.lan",
        )
        for (value in allowed) {
            assertNotNull(value, LocalEndpointPolicy.requireServiceBase(value, allowPrivateHttp = true))
        }
    }

    @Test fun publicPlaintextIsRejected() {
        val rejected = listOf(
            "http://8.8.8.8",
            "http://example.com",
            "http://172.32.0.1",
            "http://192.169.0.1",
            "http://1.1.1.1/v1",
        )
        for (value in rejected) {
            assertThrows(value, IllegalArgumentException::class.java) {
                LocalEndpointPolicy.requireServiceBase(value, allowPrivateHttp = true)
            }
        }
    }

    @Test fun cloudEndpointsRequireHttps() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalEndpointPolicy.requireServiceBase("http://127.0.0.1:8000", allowPrivateHttp = false)
        }
        assertNotNull(
            LocalEndpointPolicy.requireServiceBase("https://dashscope.aliyuncs.com", allowPrivateHttp = false),
        )
    }

    @Test fun credentialsAndQueryStringsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalEndpointPolicy.requireServiceBase("https://user:pass@example.com", allowPrivateHttp = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalEndpointPolicy.requireServiceBase("https://example.com/api?x=1", allowPrivateHttp = false)
        }
    }

    @Test fun servicePathRejectsTraversalAndQuery() {
        assertThrows(IllegalArgumentException::class.java) { LocalEndpointPolicy.requireServicePath("/v1/../etc") }
        assertThrows(IllegalArgumentException::class.java) { LocalEndpointPolicy.requireServicePath("/v1/audio?x=1") }
        assertThrows(IllegalArgumentException::class.java) { LocalEndpointPolicy.requireServicePath("v1/audio") }
        assertNotNull(LocalEndpointPolicy.requireServicePath("/v1/audio/speech"))
    }
}
