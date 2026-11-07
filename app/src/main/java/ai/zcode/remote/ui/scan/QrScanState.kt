package ai.zcode.remote.ui.scan

/** 仅由主线程修改；每次切换扫描阶段都换代，避免已排队的相机结果穿越相册或后台。 */
internal class QrScanState(galleryPending: Boolean = false) {
    var isResumed = false
        private set
    var isClosed = false
        private set
    var cameraEpoch = 0L
        private set
    // 重建只恢复相册占用；令牌重新生成，不延续旧实例的相机 epoch。
    var galleryToken: Long? = if (galleryPending) ++cameraEpoch else null
        private set

    val cameraEnabled: Boolean
        get() = isResumed && !isClosed && galleryToken == null

    fun resume() {
        if (!isClosed && !isResumed) {
            isResumed = true
            cameraEpoch++
        }
    }

    fun pause() {
        isResumed = false
        cameraEpoch++
    }

    fun beginGallery(): Long? {
        if (!cameraEnabled) return null
        cameraEpoch++
        return cameraEpoch.also { galleryToken = it }
    }

    fun isGalleryActive(token: Long): Boolean = !isClosed && galleryToken == token

    fun finishGallery(token: Long): Boolean {
        if (!isGalleryActive(token)) return false
        galleryToken = null
        cameraEpoch++
        return true
    }

    fun acceptsResult(epoch: Long, fromGallery: Boolean = false): Boolean =
        if (fromGallery) isResumed && isGalleryActive(epoch)
        else cameraEnabled && cameraEpoch == epoch

    fun retryCamera(epoch: Long) {
        if (acceptsResult(epoch)) cameraEpoch++
    }

    fun completeResult(epoch: Long, fromGallery: Boolean = false): Boolean {
        if (!acceptsResult(epoch, fromGallery)) return false
        close()
        return true
    }

    fun close() {
        isClosed = true
        galleryToken = null
        cameraEpoch++
    }
}
