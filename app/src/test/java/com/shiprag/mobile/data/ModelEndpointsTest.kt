package com.shiprag.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelEndpointsTest {
    @Test fun llamaCppAutoPaths() {
        assertEquals("http://127.0.0.1:8080/v1/models", LocalModels.endpoint("http://127.0.0.1:8080", "models"))
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", LocalModels.endpoint("http://127.0.0.1:8080", "chat"))
        assertEquals("http://127.0.0.1:8090/embed", LocalModels.endpoint("http://127.0.0.1:8090/embed", "legacy_embed"))
    }
    @Test fun cloudBasePath() {
        assertEquals("https://api.groq.com/openai/v1/models", LocalModels.endpoint("https://api.groq.com/openai/v1", "models"))
        assertEquals("https://api.groq.com/openai/v1/chat/completions", LocalModels.endpoint("https://api.groq.com/openai/v1/chat/completions", "chat"))
    }
    @Test fun denyUnencryptedPublicHosts() {
        assertThrows(IllegalArgumentException::class.java) { LocalModels.endpoint("http://api.example.com/v1", "chat") }
        assertThrows(IllegalArgumentException::class.java) { LocalModels.endpoint("http://10.1.2.3.evil.com", "chat") }
        assertThrows(IllegalArgumentException::class.java) { LocalModels.endpoint("file:///etc/passwd", "chat") }
    }
}