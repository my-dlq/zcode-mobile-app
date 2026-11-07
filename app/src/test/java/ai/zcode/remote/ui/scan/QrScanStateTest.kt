package ai.zcode.remote.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanStateTest {
    @Test
    fun cameraRequiresResumedActivity() {
        val state = QrScanState()
        assertFalse(state.cameraEnabled)
        assertFalse(state.acceptsResult(state.cameraEpoch))
        state.resume()
        assertTrue(state.cameraEnabled)
        assertTrue(state.acceptsResult(state.cameraEpoch))
        state.pause()
        assertFalse(state.cameraEnabled)
    }

    @Test
    fun invalidCameraResultCanRetryButCannotBeDeliveredTwice() {
        val state = resumedState()
        val invalidEpoch = state.cameraEpoch
        state.retryCamera(invalidEpoch)
        assertTrue(state.cameraEnabled)
        assertFalse(state.acceptsResult(invalidEpoch))
        assertTrue(state.completeResult(state.cameraEpoch))
    }

    @Test
    fun queuedCameraResultIsRejectedDuringGalleryAndAfterCancellation() {
        val state = resumedState()
        val queuedCameraEpoch = state.cameraEpoch
        val token = state.beginGallery()!!
        assertFalse(state.cameraEnabled)
        assertFalse(state.acceptsResult(queuedCameraEpoch))
        assertFalse(state.completeResult(queuedCameraEpoch))
        assertTrue(state.finishGallery(token))
        assertTrue(state.cameraEnabled)
        // 只检查“当前不在相册”会误收取消之前排队的相机结果，因此必须校验 epoch。
        assertFalse(state.acceptsResult(queuedCameraEpoch))
        assertFalse(state.completeResult(queuedCameraEpoch))
        assertTrue(state.acceptsResult(state.cameraEpoch))
    }

    @Test
    fun galleryCancellationBeforeResumeDoesNotEnableCamera() {
        val state = resumedState()
        val token = state.beginGallery()!!
        state.pause()
        assertTrue(state.finishGallery(token))
        assertFalse(state.cameraEnabled)
        state.resume()
        assertTrue(state.cameraEnabled)
    }

    @Test
    fun galleryDecodeRetainsOwnershipAcrossResumeUntilResultIsHandled() {
        val state = resumedState()
        val token = state.beginGallery()!!
        state.pause()
        assertTrue(state.isGalleryActive(token))
        assertFalse(state.acceptsResult(token, fromGallery = true))
        assertFalse(state.completeResult(token, fromGallery = true))
        state.resume()
        assertFalse(state.cameraEnabled)
        assertTrue(state.acceptsResult(token, fromGallery = true))
        assertTrue(state.completeResult(token, fromGallery = true))
    }

    @Test
    fun galleryFailureAllowsNextCameraOrGalleryAttempt() {
        val state = resumedState()
        val token = state.beginGallery()!!
        // 无二维码、非法 URL、解码异常和 OOM 均通过同一释放入口恢复相机。
        assertTrue(state.finishGallery(token))
        assertNull(state.galleryToken)
        assertTrue(state.cameraEnabled)
        assertFalse(state.acceptsResult(token, fromGallery = true))
        assertNotNull(state.beginGallery())
    }

    @Test
    fun staleGalleryResultCannotReleaseNewGalleryAttempt() {
        val state = resumedState()
        val first = state.beginGallery()!!
        state.finishGallery(first)
        val second = state.beginGallery()!!
        assertFalse(state.finishGallery(first))
        assertFalse(state.completeResult(first, fromGallery = true))
        assertTrue(state.isGalleryActive(second))
        assertFalse(state.cameraEnabled)
    }

    @Test
    fun galleryCannotBeOpenedTwiceOrWhilePaused() {
        val state = QrScanState()
        assertNull(state.beginGallery())
        state.resume()
        assertNotNull(state.beginGallery())
        assertNull(state.beginGallery())
    }

    @Test
    fun queuedCameraResultCannotCrossPauseAndResume() {
        val state = resumedState()
        val oldEpoch = state.cameraEpoch
        state.pause()
        assertFalse(state.completeResult(oldEpoch))
        state.resume()
        assertFalse(state.completeResult(oldEpoch))
        assertTrue(state.completeResult(state.cameraEpoch))
    }

    @Test
    fun cameraSuccessClosesBothSourcesExactlyOnce() {
        val state = resumedState()
        val epoch = state.cameraEpoch
        var savesAndStarts = 0
        repeat(2) {
            if (state.completeResult(epoch)) savesAndStarts++
        }
        assertEquals(1, savesAndStarts)
        assertTrue(state.isClosed)
        assertFalse(state.cameraEnabled)
        assertNull(state.beginGallery())
        state.resume()
        assertFalse(state.cameraEnabled)
    }

    @Test
    fun gallerySuccessClosesBothSourcesExactlyOnce() {
        val state = resumedState()
        val cameraEpoch = state.cameraEpoch
        val token = state.beginGallery()!!
        var savesAndStarts = 0
        if (state.completeResult(token, fromGallery = true)) savesAndStarts++
        if (state.completeResult(cameraEpoch)) savesAndStarts++
        if (state.completeResult(token, fromGallery = true)) savesAndStarts++
        assertEquals(1, savesAndStarts)
        assertFalse(state.cameraEnabled)
        assertFalse(state.finishGallery(token))
    }

    @Test
    fun exitRejectsLateCameraAndGalleryResultsEvenAfterResume() {
        val cameraState = resumedState()
        val cameraEpoch = cameraState.cameraEpoch
        cameraState.close()
        cameraState.resume()
        assertFalse(cameraState.completeResult(cameraEpoch))
        assertFalse(cameraState.cameraEnabled)

        val galleryState = resumedState()
        val token = galleryState.beginGallery()!!
        galleryState.close()
        galleryState.resume()
        assertFalse(galleryState.isGalleryActive(token))
        assertFalse(galleryState.completeResult(token, fromGallery = true))
        assertFalse(galleryState.finishGallery(token))
        assertFalse(galleryState.cameraEnabled)
    }

    @Test
    fun restoredGalleryWaitsForResumeAndAcceptsReturnedImageOnce() {
        val state = QrScanState(galleryPending = true)
        val token = state.galleryToken!!
        assertFalse(state.cameraEnabled)
        assertFalse(state.completeResult(token, fromGallery = true))
        state.resume()
        assertFalse(state.cameraEnabled)
        assertTrue(state.completeResult(token, fromGallery = true))
        assertFalse(state.completeResult(token, fromGallery = true))
    }

    @Test
    fun restoredGalleryCancellationReleasesCameraOnlyAfterResume() {
        val state = QrScanState(galleryPending = true)
        assertTrue(state.finishGallery(state.galleryToken!!))
        assertFalse(state.cameraEnabled)
        state.resume()
        assertTrue(state.cameraEnabled)
        assertTrue(state.acceptsResult(state.cameraEpoch))
    }

    @Test
    fun recreationRestoresOnlyGalleryPhaseNotOldCameraEpoch() {
        val old = resumedState()
        repeat(5) { old.pause(); old.resume() }
        old.beginGallery()
        val oldEpoch = old.cameraEpoch
        val restored = QrScanState(galleryPending = old.galleryToken != null)
        old.close()
        restored.resume()
        assertTrue(restored.galleryToken != null)
        assertFalse(restored.acceptsResult(oldEpoch))
        assertFalse(old.completeResult(oldEpoch))
        assertTrue(restored.finishGallery(restored.galleryToken!!))
        assertTrue(restored.cameraEnabled)
    }

    private fun resumedState() = QrScanState().apply { resume() }
}
