}
}

                        DownloadManager.STATUS_FAILED -> {
                       DownloadManager.STATUS_FAILED -> {

    val reason = it.getInt(
        it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
    )

    val reasonText = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "無法繼續下載"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "找不到儲存裝置"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "檔案已存在"
        DownloadManager.ERROR_FILE_ERROR -> "檔案存取錯誤"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP 資料錯誤"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "儲存空間不足"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重新導向次數過多"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "HTTP 狀態碼錯誤"
        DownloadManager.ERROR_UNKNOWN -> "未知下載錯誤"
        else -> "錯誤代碼：$reason"
    }

                            activeDownloads.remove(url)
    activeDownloads.remove(url)

                            sendProgress(url, 0, "failed")
    sendProgress(url, 0, "failed")

                            Toast.makeText(
                                this@MainActivity,
                                "下載失敗：$fileName",
                                Toast.LENGTH_LONG
                            ).show()
                        }
    Toast.makeText(
        this@MainActivity,
        "下載失敗：$fileName\n$reasonText（$reason）",
        Toast.LENGTH_LONG
    ).show()
}
}
}
}
