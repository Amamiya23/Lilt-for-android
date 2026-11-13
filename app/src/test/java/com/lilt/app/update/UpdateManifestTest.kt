package com.lilt.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateManifestTest {
    @Test fun `only signed manifest bytes are accepted`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(keys.public.encoded)}\n-----END PUBLIC KEY-----"
        val manifest = """{"versionName":"0.2.0","versionCode":2,"packageName":"com.lilt.app","minSdk":31,"apkUrl":"https://example.com/Lilt.apk","sha256":"${"a".repeat(64)}","size":1024,"notes":"Changes"}""".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(manifest)
            sign()
        }
        assertEquals(2, verifyUpdateManifest(manifest, signature, pem).versionCode)
        val tampered = manifest.toString(Charsets.UTF_8).replace("0.2.0", "0.3.0").toByteArray()
        assertThrows(IllegalStateException::class.java) { verifyUpdateManifest(tampered, signature, pem) }
    }
}
