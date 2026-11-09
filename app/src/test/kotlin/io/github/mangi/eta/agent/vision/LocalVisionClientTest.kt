package io.github.mangi.eta.agent.vision

import io.github.mangi.eta.data.model.ImageRecognitionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalVisionClientTest {
    private fun settings(baseUrl: String, path: String = "/v1/chat/completions") =
        ImageRecognitionSettings(enabled = true, baseUrl = baseUrl, path = path, model = "qwen2.5-vl")

    @Test fun publicEndpointsAreRejectedSoImagesCannotLeaveTheLan() {
        for (base in listOf("https://api.openai.com", "http://8.8.8.8:8000", "https://example.com")) {
            assertThrows(base, VisionFailure::class.java) { LocalVisionClient.endpoint(settings(base)) }
        }
    }

    @Test fun lanAndLoopbackEndpointsAreAccepted() {
        assertEquals(
            "http://192.168.1.10:8080/v1/chat/completions",
            LocalVisionClient.endpoint(settings("http://192.168.1.10:8080")),
        )
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            LocalVisionClient.endpoint(settings("http://127.0.0.1:11434")),
        )
    }

    @Test fun pathsCannotEscapeTheServiceRoot() {
        assertThrows(VisionFailure::class.java) { LocalVisionClient.endpoint(settings("http://127.0.0.1:11434", "/../etc")) }
        assertThrows(VisionFailure::class.java) {
            LocalVisionClient.endpoint(settings("http://127.0.0.1:11434", "/v1/chat?x=1"))
        }
    }

    @Test fun modelIsRequiredWhenSaving() {
        assertThrows(VisionFailure::class.java) {
            LocalVisionClient.validate(ImageRecognitionSettings(enabled = true, model = ""))
        }
        LocalVisionClient.validate(ImageRecognitionSettings(enabled = true, model = "qwen2.5-vl"))
    }
}
