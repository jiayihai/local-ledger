package cn.localledger.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import cn.localledger.core.LedgerDateRange
import cn.localledger.core.Snapshot
import cn.localledger.core.XlsxWriter

internal class FlowDocumentContract : ActivityResultContracts.CreateDocument(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal data class FlowExport(val snapshot: Snapshot, val range: LedgerDateRange, val categoryScope: String?) {
    val fileName = "流水_${range.start}_${range.end}.xlsx"

    fun writeTo(resolver: ContentResolver, uri: Uri) {
        val stream = resolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
        stream.use { XlsxWriter.write(snapshot, it, range = range, categoryScope = categoryScope) }
    }
}
