package com.thinktank.recorder.ondevice.summary

import java.security.MessageDigest

internal fun sourceHash(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
