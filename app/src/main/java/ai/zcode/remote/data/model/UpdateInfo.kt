package ai.zcode.remote.data.model

import java.io.Serializable

/**
 * GitHub Release 更新信息
 *
 * @property tagName Release 的 tag（如 v0.1.2）
 * @property versionName 去掉 v 前缀后的版本号（如 0.1.2）
 * @property downloadUrl APK 资产的浏览器下载地址
 * @property fileSize APK 文件大小（字节）
 * @property releaseNotes Release 说明（body 原文，可能为空）
 */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val downloadUrl: String,
    val fileSize: Long,
    val releaseNotes: String
) : Serializable
