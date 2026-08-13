package com.coreline.ai.voice.ondevice.modelpack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelVaultContractTest {
    @Test
    fun disconnectedVaultDoesNotClaimPersistentAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ModelVault(context).disconnect()

        val state = ModelVault(context).state()

        assertEquals(ModelVaultConnection.NOT_CONNECTED, state.connection)
        assertFalse(state.connected)
    }

    @Test
    fun artifactNamesAreStableAndContentAddressed() {
        val gemma = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)
        val senseVoice = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)

        assertEquals(
            "gemma_summary_ko-${gemma.expectedSha256}.litertlm",
            gemma.artifactFileName(),
        )
        assertEquals(
            "sensevoice_stt_ko-${senseVoice.expectedSha256}.tar.bz2",
            senseVoice.artifactFileName(),
        )
    }

    @Test
    fun connectionContractDistinguishesMissingPermissionAndUsableRoot() {
        assertEquals(
            ModelVaultConnection.NOT_CONNECTED,
            modelVaultConnection(
                hasStoredUri = false,
                hasReadWritePermission = false,
                rootUsable = false,
            ),
        )
        assertEquals(
            ModelVaultConnection.PERMISSION_REQUIRED,
            modelVaultConnection(
                hasStoredUri = true,
                hasReadWritePermission = false,
                rootUsable = true,
            ),
        )
        assertEquals(
            ModelVaultConnection.PERMISSION_REQUIRED,
            modelVaultConnection(
                hasStoredUri = true,
                hasReadWritePermission = true,
                rootUsable = false,
            ),
        )
        assertEquals(
            ModelVaultConnection.CONNECTED,
            modelVaultConnection(
                hasStoredUri = true,
                hasReadWritePermission = true,
                rootUsable = true,
            ),
        )
    }
}
