package com.doubao.repkg

/**
 * 一个新版本的描述，来自 update.json。
 *
 * @param versionCode   远端 versionCode（比较版本一律用它，不要比 versionName 字符串）
 * @param versionName   远端版本名，如 "1.7.0"
 * @param title         弹窗标题
 * @param notes         更新说明要点
 * @param force         是否强制更新（`force=true` 或本地版本低于 minSupportedVersionCode）
 * @param downloadUrl   APK 下载地址（GitHub Release 资产 / 网盘链接均可）
 * @param sizeBytes     安装包大小，未知为 null
 * @param sha256        安装包校验值，可选
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val notes: List<String>,
    val force: Boolean,
    val downloadUrl: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null
)
